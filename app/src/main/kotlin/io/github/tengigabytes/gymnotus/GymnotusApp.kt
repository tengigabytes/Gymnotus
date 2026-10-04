package io.github.tengigabytes.gymnotus

import android.app.Application
import io.github.tengigabytes.gymnotus.sampler.Sampler

class GymnotusApp : Application() {
    /** Shared by the screen and the logging service. */
    val sampler by lazy { Sampler(this) }
}
