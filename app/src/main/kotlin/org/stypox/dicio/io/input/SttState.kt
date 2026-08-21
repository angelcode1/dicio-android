/*
 * Taken from /e/OS Assistant
 *
 * Copyright (C) 2024 MURENA SAS
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.stypox.dicio.io.input

import org.stypox.dicio.ui.util.Progress

/**
 * UI-facing state shared by speech-to-text input implementations. Engine-specific objects remain
 * inside the input device so the rest of Dicio does not depend on a recognizer implementation.
 */
sealed interface SttState {
    /**
     * Should never be generated directly by a [SttInputDevice]. This is used by the UI layer,
     * since permission checks can only be done there.
     */
    data object NoMicrophonePermission : SttState

    /** The STT engine has not been initialized yet. */
    data object NotInitialized : SttState

    /** The STT engine cannot be made available for the current configuration. */
    data object NotAvailable : SttState

    /** The model is not present on disk. */
    data object NotDownloaded : SttState

    data class Downloading(
        val progress: Progress,
    ) : SttState

    data class ErrorDownloading(
        val throwable: Throwable
    ) : SttState

    data object Downloaded : SttState

    /** Some model providers may require an extraction step after download. */
    data class Unzipping(
        val progress: Progress,
    ) : SttState

    data class ErrorUnzipping(
        val throwable: Throwable
    ) : SttState

    /** The model is available on disk, but is not loaded in memory yet. */
    data object NotLoaded : SttState

    /**
     * The model is being loaded, and [thenStartListening] indicates whether listening should begin
     * immediately once loading finishes.
     */
    data class Loading(
        val thenStartListening: Boolean
    ) : SttState

    data class ErrorLoading(
        val throwable: Throwable
    ) : SttState

    /** The model is ready in memory. */
    data object Loaded : SttState

    /** The model is actively listening. */
    data object Listening : SttState

    /**
     * An external Android app has been asked to listen and may still be loading. The UI therefore
     * displays a waiting state rather than claiming that recording has started.
     */
    data object WaitingForResult : SttState
}
