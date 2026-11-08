package com.example.core.media

import java.io.File
import java.io.RandomAccessFile

/** Bounded ISO-BMFF container admission; not a codec decoder or metadata remover. */
internal object PrivateMp4Preflight {
    fun validate(file:File,userApprovedOriginalVideo:Boolean) {
        require(userApprovedOriginalVideo) { "Video konum ve cihaz metaverisi içerebilir; onay gerekiyor." }
        require(file.isFile && file.length() in 32..PrivateMediaTransferBudget.MAX_PLAIN_BYTES)
        RandomAccessFile(file,"r").use { source ->
            val total=source.length();var offset=0L;var count=0;var movie=false;var media=false
            while(offset<total) {
                require(++count<=4096 && total-offset>=8) { "Video kapsayıcısı eksik veya aşırı karmaşık." }
                source.seek(offset)
                var size=source.readInt().toLong() and 0xffffffffL
                val type=ByteArray(4).also(source::readFully).toString(Charsets.US_ASCII)
                var header=8L
                if(size==1L) { require(total-offset>=16);size=source.readLong();header=16 }
                if(size==0L) size=total-offset
                require(size>=header && size<=total-offset)
                if(count==1) {
                    require(type=="ftyp" && size>=16 && size<=4096)
                    val brand=ByteArray(4).also(source::readFully).toString(Charsets.US_ASCII)
                    require(brand in setOf("isom","iso2","iso5","iso6","mp41","mp42","avc1","M4V ")) {
                        "Bu video MP4 biçimi desteklenmiyor."
                    }
                } else require(type!="ftyp")
                if(type=="moov") {require(!movie && size>header);movie=true}
                if(type=="mdat") {require(size>header);media=true}
                offset+=size
            }
            require(offset==total && movie && media && source.length()==total) { "Video kapsayıcısı tamamlanmamış." }
        }
    }
}
