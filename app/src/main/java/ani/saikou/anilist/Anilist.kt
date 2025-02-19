package ani.saikou.anilist

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.io.File

object Anilist {
    val query : AnilistQueries = AnilistQueries()
    val mutation : AnilistMutations = AnilistMutations()

    var token : String? = null
    var username : String? = null
    var adult : Boolean = false
    var userid : Int? = null
    var avatar : String? = null
    var bg : String? = null
    var episodesWatched : Int? = null
    var chapterRead : Int? = null
    var genres:ArrayList<String>?=null
    var tags:ArrayList<String>?=null
    var sortBy = mapOf(
        Pair("Score","SCORE_DESC"),
        Pair("Popular","POPULARITY_DESC"),
        Pair("Trending","TRENDING_DESC"),
        Pair("A-Z","TITLE_ENGLISH"),
        Pair("Z-A","TITLE_ENGLISH_DESC"),
        Pair("Trash","SCORE"),
    )

    fun loginIntent(context: Context){
        val clientID = 6818
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse("https://anilist.co/api/v2/oauth/authorize?client_id=$clientID&response_type=token"))
    }

    fun getSavedToken(context: Context):Boolean{
        if ("anilistToken" in context.fileList()){
            token = File(context.filesDir, "anilistToken").readText()
            return true
        }
        return false
    }
}
