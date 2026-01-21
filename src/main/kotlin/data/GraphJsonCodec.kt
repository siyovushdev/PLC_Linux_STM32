package data// package com.example.simpleplc.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import domain.PlcGraphDef

private val prettyGson = GsonBuilder()
    .setPrettyPrinting()
    .disableHtmlEscaping()
    .create()

fun prettyJson(raw: String): String = try {
    prettyGson.toJson(JsonParser.parseString(raw))
} catch (e: Throwable) {
    "<< INVALID JSON >>\n$raw"
}


object GraphJsonCodec {
    private val gson = Gson()

    fun toJson(graph: PlcGraphDef): String = gson.toJson(graph)

    fun fromJson(json: String): PlcGraphDef =
        gson.fromJson(json, PlcGraphDef::class.java)
}


