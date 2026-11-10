package com.rotfarmer.ereader.sketch

import android.graphics.Bitmap
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.*
import android.widget.*
import com.rotfarmer.ereader.ui.*
import kotlinx.coroutines.*

/** The browser holds metadata and a small thumbnail cache, never 350 page contents. */
class SketchPageBrowser(private val host:SketchActivity,private val pages:List<PageInfo>) {
    fun show() {
        val ui=host.colors;val body=host.column();var query="";var filter=0;var shown=pages
        val jobs=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val cache=object:LruCache<Long,Bitmap>(8*1024*1024){override fun sizeOf(key:Long,value:Bitmap)=value.allocationByteCount}
        val requests=mutableSetOf<Long>()
        val search=EditText(host).apply { hint="Page number, title or section";setTextColor(ui.fg);setHintTextColor(ui.muted);setSingleLine() }
        body.addView(search)
        val filters=LinearLayout(host);body.addView(filters)
        val grid=GridView(host).apply { numColumns=GridView.AUTO_FIT;columnWidth=host.dp(145);horizontalSpacing=host.dp(12);verticalSpacing=host.dp(12) }
        val adapter=object:BaseAdapter(){override fun getCount()=shown.size;override fun getItem(position:Int)=shown[position];override fun getItemId(position:Int)=shown[position].id
            override fun getView(position:Int,recycled:View?,parent:ViewGroup):View {
                val p=shown[position]
                val cell=(recycled as? LinearLayout) ?: LinearLayout(host).apply { orientation=LinearLayout.VERTICAL
                    addView(ImageView(host).apply { scaleType=ImageView.ScaleType.FIT_CENTER },LinearLayout.LayoutParams(-1,host.dp(175)))
                    addView(label(host,"",ui,13f,bold=true));addView(label(host,"",ui,11f,ui.muted)) }
                cell.tag=p.id;val image=cell.getChildAt(0) as ImageView
                image.setBackgroundColor(if(host.settings.dark)0xFF202020.toInt() else android.graphics.Color.WHITE);image.setImageBitmap(cache.get(p.id))
                (cell.getChildAt(1) as TextView).text="${p.index+1}${if(p.bookmarked)" ★" else ""} ${p.title}"
                (cell.getChildAt(2) as TextView).text=p.section
                if(p.used && cache.get(p.id)==null && requests.add(p.id))jobs.launch {
                    try { val bitmap=withContext(Dispatchers.IO){SketchRenderer.thumbnail(host.store.referencesDir,host.store.pageById(host.bookId,p.id),180,host.settings.dark)};cache.put(p.id,bitmap);notifyDataSetChanged() }
                    finally { requests.remove(p.id) }
                }
                return cell
            }
        }
        grid.adapter=adapter
        fun update(){shown=pages.filter { (filter!=1 || it.used) && (filter!=2 || it.bookmarked) && (query.isBlank() || "${it.index+1}"==query || it.title.contains(query,true) || it.section.contains(query,true)) };adapter.notifyDataSetChanged()}
        listOf("All","Used","Bookmarked").forEachIndexed { i,name->filters.addView(host.button(name){filter=i;update()}) }
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){query=s.toString().trim();update()};override fun afterTextChanged(s:Editable?){} })
        body.addView(grid,LinearLayout.LayoutParams(-1,minOf(host.dp(480),host.resources.displayMetrics.heightPixels/2)))
        val dialog=host.sheet("Pages",body)
        grid.setOnItemClickListener { _,_,position,_->dialog.dismiss();host.navigateId(shown[position].id) }
        grid.setOnItemLongClickListener { _,_,position,_->dialog.dismiss();host.pageMenu(shown[position]);true }
        dialog.setOnDismissListener { jobs.cancel();cache.evictAll() }
    }
}
