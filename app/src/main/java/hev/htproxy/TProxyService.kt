package hev.htproxy

/**
 * Jembatan JNI ke hev-socks5-tunnel (tun2socks).
 * Nama package/class HARUS hev.htproxy.TProxyService, sesuai -DPKGNAME/-DCLSNAME di Application.mk.
 */
@Suppress("FunctionName")
class TProxyService {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic external fun TProxyStopService()
        @JvmStatic external fun TProxyGetStats(): LongArray?
    }
}
