package com.golfapp.swingly.analyzer

import com.golfapp.swingly.dao.FullSwing
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@Serializable
data class FullSwingResultData (
    var videoID: String = "",
    var backSwingResultData: BackSwingResultData = BackSwingResultData()
    // TODO: Do downswing and follow through data
)

class FullSwingAnalyzer(fullSwingData: FullSwing) {
    private val mBackSwingAnalyzer = BackSwingAnalyzer(fullSwingData.backSwingData)

    fun run() : FullSwingResultData {
        val resultData = FullSwingResultData()
        runBlocking {
            val backSwingResults = launch {
                mBackSwingAnalyzer.run()
                // TODO: run the other data analyzers
                resultData.backSwingResultData = mBackSwingAnalyzer.swingData
            }

            backSwingResults.join()
        }

        return resultData
    }
}