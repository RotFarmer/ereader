package com.rotfarmer.ereader.sketch

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.*
import com.rotfarmer.ereader.App
import com.rotfarmer.ereader.ink.Tools
import com.rotfarmer.ereader.ui.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Library sketchbook editor. Rendering, geometry, IO and page browsing have independent owners. */
class SketchActivity:Activity(),PenToolbar.Callbacks {
    private val app by lazy { App.of(this) }
    internal val settings get()=app.settings
    internal val colors get()=UiColors(settings.dark)
    internal val store by lazy { SketchStore(this) }
    internal val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    internal var bookId=-1L;private set
    internal var book:Sketchbook?=null;private set
    internal var info:PageInfo?=null;private set
    internal var page=SketchPage(0,emptyList(),emptyList());private set
    private lateinit var writer:SketchWriter
    private lateinit var root:LinearLayout
    private lateinit var header:LinearLayout
    private lateinit var workspace:LinearLayout
    private lateinit var frame:FrameLayout
    internal lateinit var canvas:SketchCanvas;private set
    private lateinit var status:TextView
    private lateinit var title:TextView
    private lateinit var counter:TextView
    private lateinit var tools:TextButton
    private lateinit var reveal:TextButton
    private var toolbar:PenToolbar?=null
    private var dock:LinearLayout?=null
    private var dockKey=""
    private var dockRefId=-1L
    private var historyGeneration=0
    private var focus=false
    private var loading=false
    private var exportKind="pdf"
    private var exportIds=emptyList<Long>()
    private var exportDark=false
    private var pendingImport:SketchImportSession?=null
    private var initial=true

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);bookId=intent.getLongExtra(EXTRA_BOOK,-1)
        writer=SketchWriter.get(this,bookId);build()
        writer.listener={ state -> status.text=state;if(!writer.busy)refreshHistory() }
        status.text=writer.state
        io({val b=store.get(bookId) ?: error("Sketchbook missing");Triple(b,store.info(bookId,b.currentPage),store.page(bookId,b.currentPage))}) {
            book=it.first;show(it.second,it.third)
            if(initial){initial=false;receiveExcerpt()}
        }
    }
    override fun onDestroy(){writer.listener=null;scope.cancel();if(::canvas.isInitialized)canvas.dispose();pendingImport?.close();super.onDestroy()}
    override fun onConfigurationChanged(newConfig:Configuration){super.onConfigurationChanged(newConfig);build();info?.let { canvas.setPage(page,it) };refreshHistory()}
    @Deprecated("Activity back callback") override fun onBackPressed(){writer.afterSaved { finish() };if(writer.error!=null)toast("Tap the save message to retry before leaving.")}
    private fun build() {
        if(::canvas.isInitialized)canvas.dispose()
        dock=null;dockKey=""
        val ui=colors
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(ui.bg)
            setOnApplyWindowInsetsListener { v,ins -> val bars=ins.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);ins } }
        header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(6),0,dp(6),0) }
        header.addView(IconButton(this,Icons.BACK,ui).apply { contentDescription="Back to library";setOnClickListener { onBackPressed() } })
        title=label(this,book?.title ?: "Sketchbook",ui,17f,serif=true).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        header.addView(title,LinearLayout.LayoutParams(0,dp(48),1f).apply { gravity=Gravity.CENTER_VERTICAL })
        status=label(this,if(::writer.isInitialized)writer.state else "Loading…",ui,12f,ui.muted).apply { gravity=Gravity.CENTER;setPadding(dp(8),0,dp(8),0);setOnClickListener { writer.retry() } }
        header.addView(status)
        header.addView(IconButton(this,Icons.STORIES,ui).apply { contentDescription="References";setOnClickListener { references() } })
        header.addView(IconButton(this,Icons.LIBRARY_ADD,ui).apply { contentDescription="Import reference";setOnClickListener { pickReference() } })
        header.addView(IconButton(this,Icons.MORE,ui).apply { contentDescription="Sketchbook options";setOnClickListener { options() } })
        root.addView(header,LinearLayout.LayoutParams(-1,dp(52)))
        root.addView(View(this).apply { setBackgroundColor(ui.line) },LinearLayout.LayoutParams(-1,dp(1)))
        workspace=LinearLayout(this).apply { orientation=if(resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        frame=FrameLayout(this)
        canvas=SketchCanvas(this,settings,store.referencesDir,{before,after -> edit(before,after)},{z,x,y,r ->
            info=info?.copy(zoom=z,panX=x,panY=y,rotation=r);info?.let { writer.viewport(it) }
        })
        frame.addView(canvas,FrameLayout.LayoutParams(-1,-1))
        toolbar=PenToolbar(this,ui,settings,this,showInsertSpace=false).apply { visibility=if(settings.toolbarVisible && !focus)View.VISIBLE else View.GONE }
        frame.addView(toolbar,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL or if(settings.toolbarRight)Gravity.END else Gravity.START).apply { marginStart=dp(6);marginEnd=dp(6) })
        reveal=TextButton(this,"Show controls",ui).apply { visibility=if(focus)View.VISIBLE else View.GONE;setOnClickListener { focus=false;applyFocus() } }
        frame.addView(reveal,FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP or Gravity.END))
        workspace.addView(frame,if(workspace.orientation==LinearLayout.HORIZONTAL)LinearLayout.LayoutParams(0,-1,1f) else LinearLayout.LayoutParams(-1,0,1f))
        root.addView(workspace,LinearLayout.LayoutParams(-1,0,1f))
        val nav=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(6),0,dp(6),0) }
        nav.addView(IconButton(this,Icons.CHEV_LEFT,ui).apply { contentDescription="Previous page";setOnClickListener { navigate((info?.index ?: 0)-1) } })
        counter=label(this,"",ui,14f,bold=true).apply { gravity=Gravity.CENTER;minHeight=dp(48);contentDescription="Browse sketchbook pages";setOnClickListener { browse() } }
        nav.addView(counter,LinearLayout.LayoutParams(0,dp(48),1f))
        nav.addView(IconButton(this,Icons.CHEV_RIGHT,ui).apply { contentDescription="Next page";setOnClickListener { navigate((info?.index ?: 0)+1) } })
        tools=TextButton(this,"Draw",ui).apply { setOnClickListener { if(canvas.referenceId!=null)finishArranging() else drawingTools() } };nav.addView(tools)
        nav.addView(TextButton(this,"Fit",ui).apply { setOnClickListener { canvas.fit() } })
        nav.addView(TextButton(this,"Focus",ui).apply { setOnClickListener { focus=!focus;applyFocus() } })
        root.addView(nav,LinearLayout.LayoutParams(-1,dp(52)))
        setContentView(root);updateLabels();refreshDock();applyFocus()
    }
    private fun applyFocus(){header.visibility=if(focus)View.GONE else View.VISIBLE;root.getChildAt(root.childCount-1).visibility=if(focus)View.GONE else View.VISIBLE;toolbar?.visibility=if(settings.toolbarVisible && !focus)View.VISIBLE else View.GONE;reveal.visibility=if(focus)View.VISIBLE else View.GONE}
    private fun updateLabels(){title.text=book?.title ?: "Sketchbook";counter.text=info?.let { "${it.index+1} / ${book?.pageCount ?: 350}" } ?: "Loading…"}
    internal fun toast(text:String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    internal fun <T> io(work:()->T,done:(T)->Unit={}) {
        if(loading)return
        writer.afterSaved {
            if(isDestroyed || loading)return@afterSaved
            loading=true;canvas.enabledForInput=false
            scope.launch { try { val result=withContext(Dispatchers.IO){work()};loading=false;canvas.enabledForInput=true;done(result) }
                catch(e:CancellationException){throw e}
                catch(e:Exception){toast(e.message ?: "Could not complete this action")}
                finally { loading=false;canvas.enabledForInput=true } }
        }
        if(writer.error!=null)toast(writer.error ?: "Save failed")
    }
    private fun show(p:PageInfo,content:SketchPage){info=p;page=content;canvas.setPage(content,p);updateLabels();refreshDock();refreshHistory()}
    internal fun reload(){val id=info?.id ?: return;io({Triple(store.get(bookId)!!,store.infoById(bookId,id),store.pageById(bookId,id))}){book=it.first;show(it.second,it.third)}}
    internal fun navigate(index:Int){if(index !in 0 until (book?.pageCount ?: 0))return;io({store.setCurrentPage(bookId,index);Triple(store.get(bookId)!!,store.info(bookId,index),store.page(bookId,index))}){book=it.first;show(it.second,it.third)}}
    internal fun navigateId(id:Long){io({store.setCurrentId(bookId,id);Triple(store.get(bookId)!!,store.infoById(bookId,id),store.pageById(bookId,id))}){book=it.first;show(it.second,it.third)}}
    private fun edit(before:SketchPage,after:SketchPage){val id=info?.id ?: return;page=after;writer.enqueue(id,before,after);refreshDock()}
    internal fun edit(after:SketchPage){val before=page;page=after;canvas.setPage(after);edit(before,after)}
    private fun refreshHistory(){val id=info?.id ?: return;val request=++historyGeneration;scope.launch { val state=withContext(Dispatchers.IO){store.history(id)};if(info?.id==id && request==historyGeneration)toolbar?.setUndoState(state.first,state.second) }}
    override fun onUndo(){history(false)}
    override fun onRedo(){history(true)}
    private fun history(redo:Boolean){val id=info?.id ?: return;io({store.undo(bookId,id,redo)}){page=it;canvas.setPage(it);refreshDock();refreshHistory()}}
    override fun onToolChanged(){canvas.mode="Draw";tools.text="Draw";canvas.invalidate()}
    override fun onToolOptions(anchor:View){sheet("Pen options",ToolOptions(this,colors,settings,settings.tool,{toolbar?.refresh();canvas.invalidate()},null))}
    override fun onInsertSpace(armed:Boolean){toolbar?.insertArmed=false}
    override fun onSideChanged(){build();info?.let { canvas.setPage(page,it) }}
    override fun onExpandedChanged(){refreshHistory()}
    override fun onHide(){settings.toolbarVisible=false;toolbar?.visibility=View.GONE}
    internal fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8)) }
    internal fun button(text:String,action:()->Unit)=TextButton(this,text,colors).apply { minHeight=dp(48);setOnClickListener { action() } }
    internal fun sheet(title:String,body:View):android.app.AlertDialog = dialog(this,settings.dark).setTitle(title).setView(body).setPositiveButton("Done",null).show().also {
        it.window?.setLayout(minOf(resources.displayMetrics.widthPixels-dp(32),dp(720)),ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    internal fun input(title:String,value:String,numeric:Boolean=false,action:(String)->Unit){val input=EditText(this).apply { setTextColor(colors.fg);setHintTextColor(colors.muted);setSingleLine();setText(value);selectAll();if(numeric)inputType=InputType.TYPE_CLASS_NUMBER }
        dialog(this,settings.dark).setTitle(title).setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save"){_,_->action(input.text.toString().trim())}.show() }
    internal fun menu(title:String,items:List<String>,action:(Int)->Unit){dialog(this,settings.dark).setTitle(title).setItems(items.toTypedArray()){_,i->action(i)}.show()}
    internal fun slider(parent:LinearLayout,name:String,value:Float,min:Float=0f,max:Float=1f,changed:(Float)->Unit){val text=label(this,name,colors,14f);parent.addView(text)
        parent.addView(SeekBar(this).apply { this.max=100;progress=((value-min)/(max-min)*100).toInt().coerceIn(0,100)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener { override fun onStartTrackingTouch(s:SeekBar){};override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){text.text="$name · ${p}%"};override fun onStopTrackingTouch(s:SeekBar){changed(min+(max-min)*s.progress/100f)} }) }) }
    private fun options(){menu("Sketchbook",listOf("Paper","Page details","Add pages","Rename sketchbook","Export or backup","Restore backup","Trash","Pen and touch")){when(it){
        0->paperOptions();1->info?.let { pageMenu(it) };2->input("Add pages","50",true){n->n.toIntOrNull()?.takeIf { it in 1..10000 }?.let { count->io({store.addPages(bookId,count);store.get(bookId)}){book=it;updateLabels()} }}
        3->input("Rename sketchbook",book?.title.orEmpty()){name->if(name.isNotBlank())io({store.rename(bookId,name);store.get(bookId)}){book=it;updateLabels()}}
        4->exportOptions();5->startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="application/zip";addCategory(Intent.CATEGORY_OPENABLE) },RESTORE)
        6->trash();7->touchOptions()
    }}}
    private fun paperOptions(){val body=column();val types=listOf("Blank","Lined","Grid","Dotted");types.forEachIndexed { i,name->body.addView(button(if(page.paper==i)"✓ $name" else name){edit(page.copy(paper=i))}) }
        slider(body,"Spacing",page.spacing,.5f,3f){edit(page.copy(spacing=it))};body.addView(button("Use for new pages"){io({store.defaults(bookId,page.paper,page.spacing)}){toast("New pages will use this paper")}});sheet("Paper",body)}
    private fun touchOptions(){val body=column();body.addView(button(if(settings.fingerDraw)"Finger drawing: on" else "Finger drawing: off"){settings.fingerDraw=!settings.fingerDraw;toast(if(settings.fingerDraw)"Finger drawing on; use two fingers to navigate" else "S Pen draws; fingers navigate")})
        slider(body,"Pressure response",settings.pressure,0f,1f){settings.pressure=it;page.strokes.forEach { s->s.shape=null };canvas.invalidate()};body.addView(button("Move toolbar to ${if(settings.toolbarRight)"left" else "right"}"){settings.toolbarRight=!settings.toolbarRight;onSideChanged()});sheet("Pen and touch",body)}
    private fun drawingTools(){val modes=listOf("Draw","Lasso","Straightedge","Arrow","Rectangle","Ellipse","Copy selection","Paste","Duplicate selection","Delete selection","Eraser mode","Pen presets","Reset rotation","Show pen palette")
        menu("Drawing tools",modes){i->when(i){in 0..5->{canvas.mode=modes[i];tools.text=modes[i]};6->canvas.copySelection();7->canvas.paste();8->canvas.duplicateSelection();9->canvas.deleteSelection();10->menu("Eraser",listOf("Whole stroke","Partial stroke")){canvas.partialEraser=it==1;settings.tool=Tools.ERASER;toolbar?.refresh()};11->presets();12->canvas.resetRotation();13->{settings.toolbarVisible=true;toolbar?.visibility=View.VISIBLE}}} }
    private fun presets(){val data=runCatching { JSONArray(settings.prefs.getString("sketchPresets","[]")) }.getOrDefault(JSONArray());val names=(0 until data.length()).map { data.getJSONObject(it).getString("name") }+"Save current pen"
        menu("Pen presets",names){i->if(i==data.length())input("Preset name","Pen ${i+1}"){name->if(name.isNotBlank()){data.put(JSONObject().put("name",name).put("tool",settings.tool).put("color",settings.toolColor(settings.tool)).put("width",settings.toolWidth(settings.tool)));settings.prefs.edit().putString("sketchPresets",data.toString()).apply()}}
            else {val p=data.getJSONObject(i);settings.tool=p.getInt("tool");settings.setToolColor(settings.tool,p.getInt("color"));settings.setToolWidth(settings.tool,p.getDouble("width").toFloat());toolbar?.refresh();onToolChanged()} }
    }
    private fun browse(){io({store.pages(bookId)}){pages->SketchPageBrowser(this,pages).show()}}
    internal fun pageMenu(p:PageInfo){menu("Page ${p.index+1}",listOf("Page title","Section name",if(p.bookmarked)"Remove bookmark" else "Bookmark","Move to page…","Duplicate page","Insert blank page before","Move to Trash")){when(it){
        0->input("Page title",p.title){name->io({store.metadata(p.copy(title=name))}){if(info?.id==p.id)info=p.copy(title=name)}}
        1->input("Section name",p.section){name->io({store.metadata(p.copy(section=name))}){if(info?.id==p.id)info=p.copy(section=name)}}
        2->io({store.metadata(p.copy(bookmarked=!p.bookmarked))}){if(info?.id==p.id)info=p.copy(bookmarked=!p.bookmarked)}
        3->input("Move to page","${p.index+1}",true){n->val dest=n.toIntOrNull()?.minus(1);if(dest!=null && dest in 0 until book!!.pageCount)io({store.movePage(bookId,p.id,dest);Triple(store.get(bookId)!!,store.infoById(bookId,p.id),store.pageById(bookId,p.id))}){book=it.first;show(it.second,it.third)}}
        4->io({val id=store.duplicatePage(bookId,p.id);Triple(store.get(bookId)!!,store.infoById(bookId,id),store.pageById(bookId,id))}){book=it.first;show(it.second,it.third)}
        5->io({val id=store.insertPages(bookId,p.index,1).single();Triple(store.get(bookId)!!,store.infoById(bookId,id),store.pageById(bookId,id))}){book=it.first;show(it.second,it.third)}
        6->io({store.deletePage(bookId,p.id);val b=store.get(bookId)!!;val n=minOf(p.index,b.pageCount-1);Triple(b,store.info(bookId,n),store.page(bookId,n))}){book=it.first;show(it.second,it.third);toast("Page moved to Trash")}
    }}}
    private fun trash(){io({store.trash(bookId)}){items->if(items.isEmpty())toast("Trash is empty") else menu("Restore page",items.map { it.title }){i->io({store.restore(items[i].id,bookId);store.get(bookId)}){book=it;updateLabels()}}}}
    private fun references(){SketchReferencePanel(this).show()}
    internal fun arrangeReference(id:Long){val r=page.references.firstOrNull { it.id==id } ?: return;edit(page.copy(references=page.references.map { if(it.id==id)r.copy(locked=false,docked=false,visible=true) else it }));canvas.referenceId=id;tools.text="Done";toast("Drag the reference or its handles; pinch to scale and rotate. S Pen still draws.")}
    private fun finishArranging(){val id=canvas.referenceId;edit(page.copy(references=page.references.map { if(it.id==id)it.copy(locked=true) else it }));canvas.referenceId=null;tools.text=canvas.mode;canvas.invalidate()}
    internal fun openSource(ref:ReferenceLayer){io({app.db.book(ref.sourceBook)}){source->if(source==null)toast("The source book is no longer in the library. This reference is still available.") else {
        val launch=ReaderActivity.intent(this,ref.sourceBook);runCatching { val p=JSONObject(ref.sourceLocation);launch.putExtra(ReaderActivity.EXTRA_START,intArrayOf(p.getInt("block"),p.getInt("offset"))) };startActivity(launch)
    }}}
    private fun refreshDock(){if(!::workspace.isInitialized)return
        val refs=page.references.filter { it.docked && it.visible };val key="${info?.id}|$dockRefId|$refs";if(key==dockKey)return;dockKey=key
        dock?.let { workspace.removeView(it) };dock=null;if(refs.isEmpty())return
        val panel=column();dock=panel;val landscape=workspace.orientation==LinearLayout.HORIZONTAL
        panel.addView(label(this,"References",colors,15f,bold=true));val ref=refs.firstOrNull { it.id==dockRefId } ?: refs.first()
        val view=SketchReferenceView(this);panel.addView(view,LinearLayout.LayoutParams(-1,0,1f));panel.addView(button(ref.name){menu("Pinned references",refs.map { it.name }){dockRefId=refs[it].id;refreshDock()}})
        workspace.addView(panel,if(landscape)LinearLayout.LayoutParams(dp(280),-1) else LinearLayout.LayoutParams(-1,dp(260)))
        val grip=label(this,if(landscape)"↔ Resize" else "↕ Resize",colors,12f,colors.muted).apply { gravity=Gravity.CENTER;minHeight=dp(40) }
        panel.addView(grip,0);var initialSize=0;var initialTouch=0f
        grip.setOnTouchListener { _,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{initialSize=if(landscape)panel.width else panel.height;initialTouch=if(landscape)e.rawX else e.rawY;true};MotionEvent.ACTION_MOVE->{val delta=(initialTouch-(if(landscape)e.rawX else e.rawY)).toInt();val cap=(if(landscape)workspace.width else workspace.height)*2/3;panel.layoutParams=panel.layoutParams.apply { if(landscape)width=(initialSize+delta).coerceIn(dp(180),cap.coerceAtLeast(dp(180))) else height=(initialSize+delta).coerceIn(dp(150),cap.coerceAtLeast(dp(150))) };true};else->true} }
        scope.launch { val bmp=withContext(Dispatchers.IO){runCatching { SketchRenderer.decode(store.referencesDir,ref,1600) }.getOrNull()};if(dock===panel && bmp!=null)view.display(bmp,ref,settings.dark) else bmp?.recycle() }
    }
    private fun pickReference(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE);type="*/*";putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/jpeg","image/png","image/webp","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","text/plain")) },IMPORT)}
    private fun exportOptions(){io({store.pages(bookId)}){pages->menu("Export",listOf("Current page as PDF","Used pages as PDF","All pages as PDF","Current page as PNG","Editable backup")){choice->
        exportKind=if(choice==4)"zip" else if(choice==3)"png" else "pdf";exportIds=when(choice){1->pages.filter { it.used }.map { it.id };2,4->pages.map { it.id };else->listOfNotNull(info?.id)}
        if(exportIds.isEmpty()){toast("No used pages to export");return@menu}
        if(exportKind=="zip")createExport() else menu("Export appearance",listOf("Light paper and original colors","Dark appearance")){exportDark=it==1;createExport()}
    }}}
    private fun createExport(){startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE);type=when(exportKind){"zip"->"application/zip";"png"->"image/png";else->"application/pdf"};putExtra(Intent.EXTRA_TITLE,"${book?.title ?: "Sketchbook"}.$exportKind") },EXPORT)}
    @Deprecated("Platform activity result API") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK)return;val uri=data?.data ?: return
        when(requestCode){IMPORT->prepareImport(uri);EXPORT->io({contentResolver.openOutputStream(uri,"wt")?.use { out->when(exportKind){"zip"->SketchArchive.write(store,bookId,out);"png"->SketchExport.png(store,bookId,exportIds.single(),out,exportDark);else->SketchExport.pdf(store,bookId,exportIds,out,exportDark)} } ?: error("Could not write the selected file")}){toast("Export saved")}
            RESTORE->io({contentResolver.openInputStream(uri)?.use { SketchArchive.restore(store,it) } ?: error("Could not open backup")}){startActivity(intent(this,it));finish()}}
    }
    private fun prepareImport(uri:Uri){io({SketchImportSession.prepare(this,store,uri,contentResolver.getType(uri))}){session->pendingImport=session;SketchImportPanel(this,session){pendingImport=null}.show()}}
    private fun receiveExcerpt(){val path=intent.getStringExtra("excerpt_path");val text=intent.getStringExtra("excerpt_text");if(path==null && text==null)return
        io({SketchImportSession.excerpt(this,store,path,text,intent.getLongExtra("source_book",-1),intent.getStringExtra("source_location").orEmpty(),intent.getStringExtra("source_label").orEmpty())}){session->pendingImport=session;SketchImportPanel(this,session){pendingImport=null}.show()}}
    companion object { private const val EXTRA_BOOK="sketchbook";private const val IMPORT=51;private const val EXPORT=52;private const val RESTORE=53
        fun intent(context:android.content.Context,id:Long)=Intent(context,SketchActivity::class.java).putExtra(EXTRA_BOOK,id)
    }
}
