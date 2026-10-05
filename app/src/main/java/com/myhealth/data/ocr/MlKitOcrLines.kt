package com.myhealth.data.ocr

import com.google.mlkit.vision.text.Text
import com.myhealth.domain.engine.label.OcrLine

/** Every line of every block of an ML Kit recognition result, in reading order ([OcrLineMapper.map]). */
fun OcrLineMapper.fromText(text: Text): List<OcrLine> =
    map(text.textBlocks.flatMap { block -> block.lines }.map { line -> OcrLineMapper.RawLine(line.text, boundsOf(line)) })

private fun boundsOf(line: Text.Line): OcrLineMapper.Bounds? =
    line.boundingBox?.let { box -> OcrLineMapper.Bounds(box.left, box.top, box.right, box.bottom) }
