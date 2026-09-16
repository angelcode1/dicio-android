package org.stypox.dicio.io.wake.oww

import java.io.File
import org.tensorflow.lite.Interpreter

class OwwModel(
    melSpectrogramPath: File,
    embeddingPath: File,
    wakeWordPath: File,
) : AutoCloseable {
    @Suppress("JoinDeclarationAndAssignment")
    private val melInterpreter: Interpreter
    private val embInterpreter: Interpreter
    private val wakeInterpreter: Interpreter

    // All inference buffers are reused. This path runs continuously (about every 72 ms), so even
    // small per-frame object graphs create significant GC/battery pressure over time.
    private val melInput = arrayOf(FloatArray(0))
    private val melOutput = Array(MEL_OUTPUT_COUNT) { FloatArray(MEL_FEATURE_SIZE) }
    private val melOutputTensor = arrayOf(arrayOf(melOutput))

    private val accumulatedMelOutputs =
        Array(EMB_INPUT_COUNT) { Array(MEL_FEATURE_SIZE) { FloatArray(1) } }
    private val embInputTensor = arrayOf(accumulatedMelOutputs)
    private val embOutput = Array(EMB_OUTPUT_COUNT) { FloatArray(EMB_FEATURE_SIZE) }
    private val embOutputTensor = arrayOf(arrayOf(embOutput))

    private val accumulatedEmbOutputs = Array(WAKE_INPUT_COUNT) { FloatArray(EMB_FEATURE_SIZE) }
    private val wakeInputTensor = arrayOf(accumulatedEmbOutputs)
    private val wakeOutput = FloatArray(1)
    private val wakeOutputTensor = arrayOf(wakeOutput)

    private var accumulatedMelCount = 0
    private var accumulatedEmbCount = 0
    private var isClosed = false

    init {
        melInterpreter = loadModel(melSpectrogramPath, intArrayOf(1, MEL_INPUT_COUNT))

        try {
            embInterpreter = loadModel(embeddingPath)
        } catch (t: Throwable) {
            melInterpreter.close()
            throw t
        }

        try {
            wakeInterpreter = loadModel(wakeWordPath)
        } catch (t: Throwable) {
            melInterpreter.close()
            embInterpreter.close()
            throw t
        }
    }

    fun processFrame(audio: FloatArray): Float {
        synchronized(this) {
            if (isClosed) return 0.0f
            if (audio.size != MEL_INPUT_COUNT) {
                throw IllegalArgumentException(
                    "OwwModel can only process audio frames of $MEL_INPUT_COUNT samples"
                )
            }

            melInput[0] = audio
            melInterpreter.run(melInput, melOutputTensor)

            // Shift the rolling mel window in place, then append normalized new rows. Copying
            // primitive values is cheaper than allocating ~160 FloatArray objects every frame.
            for (i in 0 until EMB_INPUT_COUNT - MEL_OUTPUT_COUNT) {
                for (feature in 0 until MEL_FEATURE_SIZE) {
                    accumulatedMelOutputs[i][feature][0] =
                        accumulatedMelOutputs[i + MEL_OUTPUT_COUNT][feature][0]
                }
            }
            for (i in 0 until MEL_OUTPUT_COUNT) {
                val destination = EMB_INPUT_COUNT - MEL_OUTPUT_COUNT + i
                for (feature in 0 until MEL_FEATURE_SIZE) {
                    accumulatedMelOutputs[destination][feature][0] =
                        (melOutput[i][feature] / 10.0f) + 2.0f
                }
            }
            accumulatedMelCount = minOf(
                EMB_INPUT_COUNT,
                accumulatedMelCount + MEL_OUTPUT_COUNT,
            )
            if (accumulatedMelCount < EMB_INPUT_COUNT) return 0.0f

            embInterpreter.run(embInputTensor, embOutputTensor)
            for (i in 0 until WAKE_INPUT_COUNT - EMB_OUTPUT_COUNT) {
                System.arraycopy(
                    accumulatedEmbOutputs[i + EMB_OUTPUT_COUNT],
                    0,
                    accumulatedEmbOutputs[i],
                    0,
                    EMB_FEATURE_SIZE,
                )
            }
            for (i in 0 until EMB_OUTPUT_COUNT) {
                System.arraycopy(
                    embOutput[i],
                    0,
                    accumulatedEmbOutputs[WAKE_INPUT_COUNT - EMB_OUTPUT_COUNT + i],
                    0,
                    EMB_FEATURE_SIZE,
                )
            }
            accumulatedEmbCount = minOf(
                WAKE_INPUT_COUNT,
                accumulatedEmbCount + EMB_OUTPUT_COUNT,
            )
            if (accumulatedEmbCount < WAKE_INPUT_COUNT) return 0.0f

            wakeInterpreter.run(wakeInputTensor, wakeOutputTensor)
            return wakeOutput[0]
        }
    }

    override fun close() {
        synchronized(this) {
            if (isClosed) return
            isClosed = true
            melInterpreter.close()
            embInterpreter.close()
            wakeInterpreter.close()
        }
    }

    companion object {
        // mel model shape is [1,x] -> [1,1,floor((x-512)/160)+1,32]
        const val MEL_INPUT_COUNT = 512 + 160 * 4 // chosen by us, 1152 samples @ 16kHz = 72ms
        const val MEL_OUTPUT_COUNT = (MEL_INPUT_COUNT - 512) / 160 + 1 // formula obtained empirically
        const val MEL_FEATURE_SIZE = 32 // also the size of features received by the emb model

        // emb model shape is [1,76,32,1] -> [1,1,1,96]
        const val EMB_INPUT_COUNT = 76 // hardcoded in the model
        const val EMB_OUTPUT_COUNT = 1
        const val EMB_FEATURE_SIZE = 96 // also the size of features received by the wake model

        // wake model shape is [1,16,96] -> [1,1]
        const val WAKE_INPUT_COUNT = 16 // hardcoded in the model

        private fun loadModel(modelPath: File, inputDims: IntArray? = null): Interpreter {
            val interpreter = Interpreter(modelPath)

            if (inputDims != null) {
                interpreter.resizeInput(0, inputDims)
            }

            interpreter.allocateTensors()
            return interpreter
        }
    }
}
