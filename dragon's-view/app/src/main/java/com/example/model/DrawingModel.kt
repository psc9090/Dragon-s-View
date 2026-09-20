package com.example.model

import android.graphics.Bitmap

data class DrawingModel(
  val title: String,
  val pages: List<DrawingPageModel> = emptyList()
)

data class DrawingPageModel(
  val pageIndex: Int,
  val width: Float = 800f,
  val height: Float = 600f,
  val elements: List<DrawingElement> = emptyList()
)

sealed interface DrawingElement

data class DrawingPath(
  val pathData: String,
  val fillColor: Int? = null,
  val strokeColor: Int = 0xFF000000.toInt(),
  val strokeWidth: Float = 2f
) : DrawingElement

data class DrawingRect(
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
  val rx: Float = 0f,
  val fillColor: Int? = 0xFFB3121B.toInt(),
  val strokeColor: Int = 0xFF6E0B14.toInt(),
  val strokeWidth: Float = 1f
) : DrawingElement

data class DrawingEllipse(
  val cx: Float,
  val cy: Float,
  val rx: Float,
  val ry: Float,
  val fillColor: Int? = null,
  val strokeColor: Int = 0xFFB3121B.toInt(),
  val strokeWidth: Float = 2f
) : DrawingElement

data class DrawingText(
  val x: Float,
  val y: Float,
  val text: String,
  val fontSize: Float = 14f,
  val color: Int = 0xFF000000.toInt()
) : DrawingElement

data class DrawingImage(
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
  val bitmap: Bitmap?
) : DrawingElement
