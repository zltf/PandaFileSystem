package cn.zhiskey.sfs.peer;

import cn.zhiskey.sfs.utils.udpsocket.UDPSocket;

/**
 * TODO: description
 *
 * @author <a href="https://www.zhiskey.cn">Zhiskey</a>
 */
public class Peer {

    private byte[] hashID = null;

    private byte[] createHashID() {
        return null;
    }

    private void joinNetWork(String seedPeerHost) {
        
    }

    public static void main(String[] args) {
//        Peer peer = new Peer();
//        peer.joinNetWork();
        UDPSocket.broadcast(54321, "test");
    }
}
