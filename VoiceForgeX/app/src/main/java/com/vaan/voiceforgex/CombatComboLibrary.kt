package com.vaan.voiceforgex

import android.content.Context

data class CombatCombo(val pack:String,val fighter:String,val number:Int,val chain:String)

object CombatComboLibrary {
    private val packFiles = listOf(
        "combat/MyCombat_Baki_Elite_Expansion_01_156.txt",
        "combat/MyCombat_Baki_Characters_624.txt"
    )

    fun load(context:Context):List<CombatCombo> {
        val out=mutableListOf<CombatCombo>()
        for(asset in packFiles){
            val text=runCatching{context.assets.open(asset).bufferedReader().use{it.readText()}}.getOrNull()?:continue
            var fighter="General"
            text.lineSequence().forEach { raw ->
                val line=raw.trim()
                if(line.startsWith("=====")&&line.endsWith("=====")) fighter=line.removePrefix("=====").removeSuffix("=====").trim().replace(Regex("\\s*\\(39\\)$"),"")
                else Regex("^(\\d{3})\\.\\s+(.+)$").matchEntire(line)?.let{m->out+=CombatCombo(asset.substringAfterLast('/').substringBeforeLast('.'),fighter,m.groupValues[1].toInt(),m.groupValues[2])}
            }
        }
        return out
    }

    fun fighters(context:Context)=load(context).map{it.fighter}.distinct().sorted()
    fun random(context:Context,fighter:String?=null):CombatCombo? {
        val pool=load(context).filter{fighter==null||fighter=="ALL FIGHTERS"||it.fighter==fighter}
        return pool.randomOrNull()
    }
}
