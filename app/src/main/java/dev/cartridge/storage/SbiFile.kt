package dev.cartridge.storage

/** Bounds the optional owner-provided LibCrypt subchannel data before native parsing. */
object SbiFile {
    fun validate(bytes: ByteArray) {
        require(bytes.size in 4..131072 && bytes.take(4) == listOf<Byte>(83,66,73,0)) { "Invalid SBI header" }
        var offset=4
        fun bcd(value: Byte): Int {
            val n=value.toInt() and 255;require((n and 15)<=9 && (n shr 4)<=9) { "Invalid SBI sector" };return (n shr 4)*10+(n and 15)
        }
        while(offset<bytes.size) {
            require(bytes.size-offset>=4) { "Incomplete SBI record" }
            val minute=bcd(bytes[offset]);val second=bcd(bytes[offset+1]);val frame=bcd(bytes[offset+2])
            require(second<60 && frame<75 && (minute*60+second)*75+frame>=150) { "Invalid SBI sector" }
            val length=when(bytes[offset+3].toInt()) { 1->14;2,3->7;else->error("Unsupported SBI record") }
            require(offset+length<=bytes.size) { "Incomplete SBI record" };offset+=length
        }
    }
}
