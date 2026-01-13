package data// package com.example.simpleplc.data

import domain.PlcGraphDef
import com.google.gson.Gson

object GraphJsonCodec {
    private val gson = Gson()

    fun toJson(graph: PlcGraphDef): String = gson.toJson(graph)

    fun fromJson(json: String): PlcGraphDef =
        gson.fromJson(json, PlcGraphDef::class.java)
}
