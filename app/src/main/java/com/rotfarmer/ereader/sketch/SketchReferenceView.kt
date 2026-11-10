package com.rotfarmer.ereader.sketch

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.rotfarmer.ereader.render.ReaderColors

/** Independently zoomable reference dock; it owns and releases its decoded image. */
class SketchReferenceView(context:Context):View(context) {
    private var bitmap:Bitmap?=null;private var ref:ReferenceLayer?=null;private var dark=false
    private var scale=1f;private var panX=0f;private var panY=0f;private var lastX=0f;private var lastY=0f
    private val detector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(d:ScaleGestureDetector):Boolean{scale=(scale*d.scaleFactor).coerceIn(1f,8f);invalidate();return true}})
    fun display(b:Bitmap,r:ReferenceLayer,dark:Boolean){bitmap?.recycle();bitmap=b;ref=r;this.dark=dark;invalidate()}
    override fun onDraw(c:Canvas){val b=bitmap ?: return;val r=ref ?: return;val fit=minOf(width.toFloat()/b.width,height.toFloat()/b.height)
        val w=b.width*fit*scale;val h=b.height*fit*scale
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter=if(dark && r.source.endsWith(".pdf",true))ReaderColors(true).pdfFilter else null }
        c.drawBitmap(b,null,RectF((width-w)/2+panX,(height-h)/2+panY,(width+w)/2+panX,(height+h)/2+panY),paint)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean { detector.onTouchEvent(e);when(e.actionMasked){MotionEvent.ACTION_DOWN->{lastX=e.x;lastY=e.y};MotionEvent.ACTION_MOVE->{if(!detector.isInProgress && e.pointerCount==1){panX+=e.x-lastX;panY+=e.y-lastY;invalidate()};lastX=e.x;lastY=e.y};MotionEvent.ACTION_UP->performClick()};return true }
    override fun performClick():Boolean{super.performClick();return true}
    override fun onDetachedFromWindow(){bitmap?.recycle();bitmap=null;super.onDetachedFromWindow()}
}
