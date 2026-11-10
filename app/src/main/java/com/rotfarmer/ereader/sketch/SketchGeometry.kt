package com.rotfarmer.ereader.sketch

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import com.rotfarmer.ereader.ink.InkStroke
import kotlin.math.*

object SketchGeometry {
    const val HEIGHT = 1.41421356f
    fun distance(x:Float,y:Float,ax:Float,ay:Float,bx:Float,by:Float):Float {
        val dx=bx-ax;val dy=by-ay;val n=dx*dx+dy*dy
        val t=if(n==0f)0f else (((x-ax)*dx+(y-ay)*dy)/n).coerceIn(0f,1f)
        return hypot(x-ax-dx*t,y-ay-dy*t)
    }
    fun hit(s:InkStroke,x:Float,y:Float,r:Float):Boolean {
        val p=s.pts;val radius=r+s.width/2
        if(p.size==3)return hypot(p[0]-x,p[1]-y)<=radius
        return (3 until p.size step 3).any { distance(x,y,p[it-3],p[it-2],p[it],p[it+1])<=radius }
    }
    fun inside(x:Float,y:Float,polygon:List<PointF>):Boolean {
        var inside=false;var j=polygon.lastIndex
        for(i in polygon.indices) { val a=polygon[i];val b=polygon[j]
            if((a.y>y)!=(b.y>y) && x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)inside=!inside;j=i
        };return inside
    }
    fun transform(s:InkStroke,m:Matrix,scale:Float=1f):InkStroke {
        val p=s.pts.copyOf();val xy=FloatArray(2)
        for(i in p.indices step 3) { xy[0]=p[i];xy[1]=p[i+1];m.mapPoints(xy);p[i]=xy[0];p[i+1]=xy[1] }
        return s.copy(pts=p,width=s.width*scale)
    }
    /** Segment/circle intersections preserve surviving portions even when sample points are sparse. */
    fun erase(s:InkStroke,x:Float,y:Float,r:Float):List<InkStroke> {
        if(!hit(s,x,y,r))return listOf(s)
        val p=s.pts;if(p.size<=3)return emptyList()
        val radius=r+s.width/2;val fragments=mutableListOf<FloatArray>();var part=mutableListOf<Float>()
        fun flush(){if(part.size>=3)fragments+=part.toFloatArray();part=mutableListOf()}
        for(i in 3 until p.size step 3) {
            val a=i-3;val dx=p[i]-p[a];val dy=p[i+1]-p[a+1]
            val aa=dx*dx+dy*dy;val bb=2*((p[a]-x)*dx+(p[a+1]-y)*dy)
            val cc=(p[a]-x).pow(2)+(p[a+1]-y).pow(2)-radius*radius
            val cuts=mutableListOf(0f,1f);val disc=bb*bb-4*aa*cc
            if(aa>0 && disc>0) { val d=sqrt(disc);listOf((-bb-d)/(2*aa),(-bb+d)/(2*aa)).filter { it>0 && it<1 }.forEach { cuts+=it } }
            cuts.sort()
            fun append(t:Float) { val xx=p[a]+dx*t;val yy=p[a+1]+dy*t
                if(part.size<3 || part[part.size-3]!=xx || part[part.size-2]!=yy) { part+=xx;part+=yy;part+=p[a+2]+(p[i+2]-p[a+2])*t }
            }
            for(k in 0 until cuts.lastIndex) { val lo=cuts[k];val hi=cuts[k+1];val mid=(lo+hi)/2
                if(hypot(p[a]+dx*mid-x,p[a+1]+dy*mid-y)>radius) { append(lo);append(hi) } else flush()
            }
        };flush();return fragments.map { s.copy(id=SketchIds.next(),pts=it) }
    }
    fun shape(mode:String,a:PointF,b:PointF):FloatArray {
        val pts=mutableListOf<Float>()
        fun point(x:Float,y:Float){pts+=x;pts+=y;pts+=0.7f}
        when(mode) {
            "Rectangle" -> { point(a.x,a.y);point(b.x,a.y);point(b.x,b.y);point(a.x,b.y);point(a.x,a.y) }
            "Ellipse" -> { val cx=(a.x+b.x)/2;val cy=(a.y+b.y)/2;repeat(97) { val t=it*2*PI/96;point(cx+abs(b.x-a.x)/2*cos(t).toFloat(),cy+abs(b.y-a.y)/2*sin(t).toFloat()) } }
            "Arrow" -> { point(a.x,a.y);point(b.x,b.y);val angle=atan2(b.y-a.y,b.x-a.x);val len=min(.05f,hypot(b.x-a.x,b.y-a.y)*.25f)
                point(b.x-len*cos(angle-.5f),b.y-len*sin(angle-.5f));point(b.x,b.y);point(b.x-len*cos(angle+.5f),b.y-len*sin(angle+.5f)) }
            else -> { point(a.x,a.y);point(b.x,b.y) }
        };return pts.toFloatArray()
    }
}

/** Fixed paper-space buckets avoid scanning every stroke for every eraser sample. */
class SketchStrokeIndex(strokes:List<InkStroke>) {
    private val cells=HashMap<Long,MutableSet<Long>>()
    private fun key(x:Int,y:Int)=(x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    init { strokes.forEach { s -> val b=s.bounds
        for(x in floor(b.left.coerceIn(-2f,3f)*10).toInt()..floor(b.right.coerceIn(-2f,3f)*10).toInt())
            for(y in floor(b.top.coerceIn(-2f,4f)*10).toInt()..floor(b.bottom.coerceIn(-2f,4f)*10).toInt()) cells.getOrPut(key(x,y)){mutableSetOf()}.add(s.id)
    } }
    fun candidates(x:Float,y:Float,r:Float):Set<Long> = buildSet {
        for(i in floor((x-r)*10).toInt()..floor((x+r)*10).toInt()) for(j in floor((y-r)*10).toInt()..floor((y+r)*10).toInt()) cells[key(i,j)]?.let { addAll(it) }
    }
}
