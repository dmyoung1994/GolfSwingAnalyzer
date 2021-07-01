package com.example.golfswinganalyzer.analyzer

import com.example.golfswinganalyzer.dao.FullSwing

class FullSwingAnalyzer(fullSwingData: FullSwing) {
    val backSwingAnalyzer = BackSwingAnalyzer(fullSwingData.backSwingData)
}