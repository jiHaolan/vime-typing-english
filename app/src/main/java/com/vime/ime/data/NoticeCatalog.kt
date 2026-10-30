package com.vime.ime.data

import org.json.JSONObject

data class NoticeComponent(val id:String, val title:String, val category:String, val license:String,
                           val version:String, val source:String, val summary:String, val documents:List<String>)

/** Packaged metadata only. Saved document selection must resolve through this catalog. */
class NoticeCatalog private constructor(val components:List<NoticeComponent>, val files:Map<String,String>) {
    companion object {
        fun parse(text:String):NoticeCatalog {
            require(text.length<=128*1024)
            val root=JSONObject(text)
            require(root.getString("format")=="vime-notices-1")
            val rawFiles=root.getJSONObject("files")
            val files=rawFiles.keys().asSequence().associateWith { name ->
                require(name.matches(Regex("licenses/[A-Za-z0-9_.-]+")) && ".." !in name)
                rawFiles.getString(name).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            }
            val rows=root.getJSONArray("components");require(rows.length() in 1..100)
            val components=(0 until rows.length()).map { i ->
                val row=rows.getJSONObject(i)
                val docs=row.getJSONArray("documents").let { list ->
                    (0 until list.length()).map { list.getString(it).also { name -> require(name in files) } }
                }
                fun field(key:String,max:Int=4096)=row.getString(key).also { require(it.length in 1..max) }
                NoticeComponent(field("id",100),field("title",200),field("category",100),field("license",200),
                    field("version",200),field("source",2048),field("summary"),docs)
            }
            require(components.map { it.id }.distinct().size==components.size)
            require(components.flatMap { it.documents }.toSet()==files.keys)
            return NoticeCatalog(components,files)
        }
    }
}
