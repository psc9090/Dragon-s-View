package com.example.model

import android.graphics.Bitmap

data class PresentationModel(
  val title: String,
  val slideWidth: Float = 960f,
  val slideHeight: Float = 540f,
  val slides: List<SlideModel> = emptyList()
)

data class SlideModel(
  val slideNumber: Int,
  val title: String = "",
  val backgroundColor: Int = 0xFFFFFFFF.toInt(),
  val elements: List<SlideElement> = emptyList(),
  val notes: String? = null
)

sealed interface SlideElement {
  val x: Float
  val y: Float
  val width: Float
  val height: Float
}

data class SlideTextBox(
  override val x: Float,
  override val y: Float,
  override val width: Float,
  override val height: Float,
  val paragraphs: List<SlideParagraph>,
  val backgroundColor: Int? = null
) : SlideElement

data class SlideParagraph(
  val runs: List<TextRun>,
  val alignment: Alignment = Alignment.LEFT,
  val isBullet: Boolean = false,
  val bulletLevel: Int = 0
)

data class SlideShape(
  override val x: Float,
  override val y: Float,
  override val width: Float,
  override val height: Float,
  val shapeType: ShapeType,
  val fillColor: Int = 0xFFB3121B.toInt(),
  val strokeColor: Int? = null,
  val strokeWidth: Float = 1f,
  val text: String? = null
) : SlideElement

data class SlideImage(
  override val x: Float,
  override val y: Float,
  override val width: Float,
  override val height: Float,
  val bitmap: Bitmap?
) : SlideElement

data class SlideTable(
  override val x: Float,
  override val y: Float,
  override val width: Float,
  override val height: Float,
  val rows: List<List<String>>
) : SlideElement

enum class ShapeType {
  RECTANGLE,
  ROUNDED_RECTANGLE,
  ELLIPSE,
  LINE,
  TRIANGLE,
  STAR,
  ARROW_RIGHT,
  CUSTOM
}
