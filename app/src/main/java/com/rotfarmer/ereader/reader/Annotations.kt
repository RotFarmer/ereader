package com.rotfarmer.ereader.reader

import com.rotfarmer.ereader.data.Bookmark
import com.rotfarmer.ereader.data.Excerpt
import com.rotfarmer.ereader.data.Highlight
import com.rotfarmer.ereader.data.Locator
import com.rotfarmer.ereader.data.WritingSpace
import com.rotfarmer.ereader.ink.InkStore

/** Everything the reader has added to the open book. */
class Annotations {
    val highlights = ArrayList<Highlight>()
    val bookmarks = ArrayList<Bookmark>()
    val excerpts = ArrayList<Excerpt>()
    val spaces = ArrayList<WritingSpace>()
    val ink = InkStore()
}

class TextSel(var start: Locator, var end: Locator)

sealed class InsertTarget {
    class Flow(val loc: Locator) : InsertTarget()
    class Pdf(val page: Int, val y: Float, val allowSplit: Boolean) : InsertTarget()
}
