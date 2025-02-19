package ani.saikou.anime.source.extractors

import android.util.Base64
import ani.saikou.anime.Episode
import ani.saikou.anime.source.Extractor
import ani.saikou.findBetween
import ani.saikou.getSize
import ani.saikou.toastString
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.jsoup.Connection
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class GogoCDN : Extractor() {
    override fun getStreamLinks(name: String, url: String): Episode.StreamLinks {
        val list = arrayListOf<Episode.Quality>()
        try {
            val response = Jsoup.connect(url)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .get()

        if(url.contains("streaming.php")) {
            response.select("script[data-name='crypto']").attr("data-value").also {
                val id = cryptoHandler(cryptoHandler(it,false).findBetween("","&")?:return@also,true)
                Jsoup.connect("https://gogoplay4.com/encrypt-ajax.php?id=$id")
                .ignoreHttpErrors(true).ignoreContentType(true)
                .header("X-Requested-With", "XMLHttpRequest").get().body().toString().apply {
                    cryptoHandler(this.findBetween("""{"data":"""","\"}")?:return@apply,false)
                    .replace("""o"<P{#meme":""","""e":[{"file":""").apply{
                        val json = this.dropLast(this.length-this.lastIndexOf('}')-1)
                        val a = arrayListOf<Deferred<*>>()
                        runBlocking {
                            fun add(i:JsonElement,backup:Boolean){
                                a.add(async {
                                    val label = i.jsonObject["label"].toString().lowercase().trim('"')
                                    val fileURL = i.jsonObject["file"].toString().trim('"')
                                    if (label != "auto p" && label != "hls p"){
                                        if(label!="auto") list.add(Episode.Quality(fileURL, label.replace(" ", ""),if(!backup) getSize(fileURL, mutableMapOf("referer" to url)) else null, if(backup) "Backup" else null)) else null
                                    }
                                    else list.add(Episode.Quality(fileURL, "Multi Quality", null,if(backup) "Backup" else null))
                                })
                            }
                            Json.decodeFromString<JsonObject>(json).apply {
                                jsonObject["source"]?.jsonArray?.forEach { i->
                                    add(i,false)
                                }
                                jsonObject["source_bk"]?.jsonArray?.forEach{i->
                                    add(i,true)
                                }
                            }
                            a.awaitAll()
                        }
                    }
                }
            }
        }

        else if (url.contains("embedplus")){
            val fileURL = response.toString().findBetween("sources:[{file: '","',")
            if(fileURL!=null && try{Jsoup.connect(fileURL).method(Connection.Method.HEAD).execute();true}catch(e:Exception){false}) {
                list.add(
                    Episode.Quality(
                        fileURL,
                        "Multi Quality",
                        null
                    )
                )
            }
        }
        }catch (e:Exception){
            toastString(e.toString())
        }
        return Episode.StreamLinks(name, list, mutableMapOf("referer" to url))
    }

    private fun cryptoHandler(string:String,encrypt:Boolean=true) : String {
        val key = "25716538522938396164662278833288".toByteArray()
        val secretKey =  SecretKeySpec(key, "AES")

        val iv = "1285672985238393".toByteArray()
        val ivParameterSpec =  IvParameterSpec(iv)

        val padding = byteArrayOf(0x8,0xe,0x3,0x8,0x9,0x3,0x4,0x9)

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        return if (!encrypt) {
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
            String(cipher.doFinal(Base64.decode(string,Base64.NO_WRAP)))
        }
        else{
            cipher.init(Cipher.ENCRYPT_MODE,secretKey,ivParameterSpec)
            Base64.encodeToString(cipher.doFinal(string.toByteArray()+padding),Base64.NO_WRAP)
        }
    }
}