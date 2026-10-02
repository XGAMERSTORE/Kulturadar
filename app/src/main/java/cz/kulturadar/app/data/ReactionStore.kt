package cz.kulturadar.app.data

import android.content.Context
import cz.kulturadar.app.model.Reaction

class ReactionStore(context: Context) {
    private val prefs = context.getSharedPreferences("reactions", Context.MODE_PRIVATE)
    fun get(id: String): Reaction = runCatching { Reaction.valueOf(prefs.getString(id, Reaction.NONE.name) ?: Reaction.NONE.name) }.getOrDefault(Reaction.NONE)
    fun set(id: String, reaction: Reaction) { prefs.edit().putString(id, reaction.name).apply() }
    fun ids(reaction: Reaction): Set<String> = prefs.all.filterValues { it == reaction.name }.keys
}
