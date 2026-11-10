package com.rotfarmer.ereader.sketch

import android.content.Intent
import android.widget.ScrollView
import com.rotfarmer.ereader.ui.ReaderActivity

class SketchReferencePanel(private val host:SketchActivity) {
    fun show() {
        if(host.page.references.isEmpty()){host.toast("Use Import reference to add an image or document.");return}
        var selected=host.page.references.first().id
        val body=host.column();val scroll=ScrollView(host).apply { addView(body) }
        val dialog=host.sheet("References",scroll)
        fun current()=host.page.references.firstOrNull { it.id==selected }
        fun render() {
            body.removeAllViews();val ref=current() ?: run { dialog.dismiss();return }
            fun change(r:ReferenceLayer){host.edit(host.page.copy(references=host.page.references.map { if(it.id==r.id)r else it }));render()}
            body.addView(host.button(ref.name){val refs=host.page.references;host.menu("Choose reference",refs.map { it.name }){selected=refs[it].id;render()}})
            body.addView(host.button(if(ref.docked)"Place on page" else "Pin beside page"){change(current()!!.copy(docked=!ref.docked))})
            body.addView(host.button("Move, scale and rotate"){host.arrangeReference(selected);dialog.dismiss()})
            body.addView(host.button(if(ref.locked)"Unlock reference" else "Lock reference"){change(current()!!.copy(locked=!ref.locked))})
            body.addView(host.button(if(ref.visible)"Hide reference" else "Show reference"){change(current()!!.copy(visible=!ref.visible))})
            host.slider(body,"Opacity",ref.opacity){change(current()!!.copy(opacity=it))}
            host.slider(body,"Dim photo in dark mode",ref.dim,0f,.8f){change(current()!!.copy(dim=it))}
            host.slider(body,"Crop left",ref.cropLeft,0f,.9f){change(current()!!.copy(cropLeft=it.coerceAtMost(current()!!.cropRight-.05f)))}
            host.slider(body,"Crop top",ref.cropTop,0f,.9f){change(current()!!.copy(cropTop=it.coerceAtMost(current()!!.cropBottom-.05f)))}
            host.slider(body,"Crop right",ref.cropRight,.1f,1f){change(current()!!.copy(cropRight=it.coerceAtLeast(current()!!.cropLeft+.05f)))}
            host.slider(body,"Crop bottom",ref.cropBottom,.1f,1f){change(current()!!.copy(cropBottom=it.coerceAtLeast(current()!!.cropTop+.05f)))}
            body.addView(host.button("Bring to front"){change(current()!!.copy(order=(host.page.references.maxOfOrNull { it.order } ?: 0)+1))})
            body.addView(host.button("Send to back"){change(current()!!.copy(order=(host.page.references.minOfOrNull { it.order } ?: 0)-1))})
            if(ref.sourceBook>=0)body.addView(host.button("Open source book"){host.openSource(ref)})
            body.addView(host.button("Remove reference"){host.edit(host.page.copy(references=host.page.references.filterNot { it.id==selected }));host.toast("Reference removed. Undo restores it; ink is preserved.");dialog.dismiss()})
        }
        render()
        dialog.window?.setLayout(minOf(host.resources.displayMetrics.widthPixels-32,720*host.resources.displayMetrics.density.toInt()),host.resources.displayMetrics.heightPixels*4/5)
    }
}
