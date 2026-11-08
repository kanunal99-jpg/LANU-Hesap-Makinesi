package com.example

import com.example.core.media.PrivateMp4Preflight
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class PrivateMp4PreflightTest {
    @get:Rule val files=TemporaryFolder()
    private fun box(type:String,payload:ByteArray)=ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
        it.writeInt(payload.size+8);it.write(type.toByteArray(Charsets.US_ASCII));it.write(payload)
    } }.toByteArray()
    private fun fixture()=box("ftyp","isom0000".toByteArray())+box("moov",ByteArray(12))+box("mdat",ByteArray(24))
    @Test fun validContainerRequiresExplicitMetadataApproval() {
        val file=files.newFile().also {it.writeBytes(fixture())}
        assertTrue(runCatching {PrivateMp4Preflight.validate(file,false)}.isFailure)
        PrivateMp4Preflight.validate(file,true)
    }
    @Test fun missingMovieTruncationAndOverflowingBoxSizesAreRejected() {
        val valid=fixture()
        for(bytes in listOf(valid.copyOf(valid.size-1),box("ftyp","isom0000".toByteArray())+box("mdat",ByteArray(24)),
            valid.copyOf().also {it[0]=0x7f},valid+byteArrayOf(0),valid+box("ftyp","isom0000".toByteArray()))) {
            val file=files.newFile().also {it.writeBytes(bytes)}
            assertTrue(runCatching {PrivateMp4Preflight.validate(file,true)}.isFailure)
        }
    }
}
