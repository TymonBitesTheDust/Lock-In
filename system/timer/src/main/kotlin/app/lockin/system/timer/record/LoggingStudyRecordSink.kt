package app.lockin.system.timer.record

import android.util.Log
import app.lockin.core.timer.FocusSegment
import app.lockin.core.timer.SessionSummary
import app.lockin.core.timer.StudyRecordSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Temporary sink so the timer runs end to end before the database exists. It persists nothing.
 * Replaced by a Room-backed sink (upsert on sessionId + index) in the Room schema step.
 */
@Singleton
class LoggingStudyRecordSink @Inject constructor() : StudyRecordSink {

    override suspend fun recordSegment(segment: FocusSegment) {
        Log.i(TAG, "segment ${segment.sessionId}#${segment.index} ${segment.durationMs}ms (${segment.endedBy})")
    }

    override suspend fun finishSession(summary: SessionSummary) {
        Log.i(TAG, "session ${summary.sessionId} finished: ${summary.focusMs}ms in ${summary.segmentCount} segments")
    }

    private companion object {
        const val TAG = "LockInRecords"
    }
}
