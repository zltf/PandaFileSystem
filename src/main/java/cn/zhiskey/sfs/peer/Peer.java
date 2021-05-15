package cn.zhiskey.sfs.peer;

import cn.zhiskey.sfs.message.Message;
import cn.zhiskey.sfs.message.MessageHandler;
import cn.zhiskey.sfs.network.Route;
import cn.zhiskey.sfs.network.RouteList;
import cn.zhiskey.sfs.utils.FileUtil;
import cn.zhiskey.sfs.utils.MacUtil;
import cn.zhiskey.sfs.utils.hash.HashIDUtil;
import cn.zhiskey.sfs.utils.config.ConfigUtil;
import cn.zhiskey.sfs.utils.XMLUtil;
import cn.zhiskey.sfs.utils.udpsocket.SparkRecvLoopThread;
import cn.zhiskey.sfs.utils.udpsocket.UDPRecvLoopThread;
import cn.zhiskey.sfs.utils.udpsocket.UDPSocket;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * P2P节点类 TODO: 描述
 *
 * @author <a href="https://www.zhiskey.cn">Zhiskey</a>
 */
public class Peer {

    private byte[] hashID = null;

    private RouteList routeList = new RouteList();

    private List<String> sparkFileList = new ArrayList<>();

    private PeerStatus status = PeerStatus.START;

    /**
     * 生成一个随机的HashID<br>
     * 基于时间戳、MAC地址、随机数生成
     *
     * @return byte[] 生成对HashID
     * @author <a href="https://www.zhiskey.cn">Zhiskey</a>
     */
    private static byte[] createHashID() {
        StringBuilder seed = new StringBuilder();

        // 时间戳
        long timeStamp = System.currentTimeMillis();
        seed.append(timeStamp);

        // MAC地址
        seed.append(MacUtil.getMacId());

        // 随机数
        seed.append(new Random().nextInt());

        // 生成哈希ID
        return HashIDUtil.getHashID(seed.toString());
    }

    /**
     * 将节点加入网络，需要提供种子节点<br>
     * 会尝试与种子节点通信，并获取种子节点的hashID
     *
     * @param seedPeerHost 种子节点Host，网络中第一个节点的种子节点是他自己：localhost
     * @author <a href="https://www.zhiskey.cn">Zhiskey</a>
     */
    public void joinNetWork(String seedPeerHost) {
        initPeer();

        // 启动消息接收循环
        new UDPRecvLoopThread(UDPSocket.getCommonRecvPort(), datagramPacket -> {
            MessageHandler messageHandler = new MessageHandler(this);
            messageHandler.handle(datagramPacket);
        }).start();

        // 启动文件接收循环
        new SparkRecvLoopThread(UDPSocket.getSparkRecvPort(), this).start();

        // 获取种子节点hashID
        Message msg = new Message("GetHashID");
        UDPSocket.send(seedPeerHost, msg);
        status = PeerStatus.WAIT_SEED_HASH_ID;
    }

    public void makeSpark(String filePath) {
        List<String> list = new ArrayList<>();
        try {
            list = FileUtil.makeSpark(new File(filePath));
        } catch (IOException e) {
            e.printStackTrace();
        }
        int sparkBakCount = Integer.parseInt(ConfigUtil.getInstance().get("sparkBakCount"));
        // 向网络中节点发送spark
        for (String hashID : list) {
            sparkFileList.add(hashID);
            List<Route> resList = routeList.searchFromRouteList(hashID, sparkBakCount);
            // 去掉自己
            resList.removeIf(route -> route.equalsByHashID(getHashID()));
            SparkRecvLoopThread.sendSpark(resList, getHashIDString(), hashID, sparkBakCount, sparkFileList);
        }
    }

    /**
     * 初始化节点<br>
     * 从XML文件读出节点数据或是新建一个节点
     *
     * @author <a href="https://www.zhiskey.cn">Zhiskey</a>
     */
    private void initPeer() {
        String path = FileUtil.getResourcesPath() + ConfigUtil.getInstance().get("peerDataPath");
        File peerXMLFile = new File(path);
        Document document;
        if (peerXMLFile.exists()) {
            document = XMLUtil.parse(peerXMLFile);
            initPeerData(document);
        } else {
            newPeer();
        }
        // 设置自己的hashID，方便后面计算前缀长
        HashIDUtil.getInstance().setSelfHashID(hashID);
    }

    private void initPeerData(Document document) {
        Element elementPeer = document.getDocumentElement();
        hashID = HashIDUtil.toBytes(elementPeer.getAttribute("hashID"));
        // 读取spark列表
        NodeList nodeListSparks = elementPeer.getElementsByTagName("sparks");
        for (int i = 0; i < nodeListSparks.getLength(); i++) {
            NodeList nodeListSpark = ((Element)nodeListSparks.item(i)).getElementsByTagName("spark");
            for (int j = 0; j < nodeListSpark.getLength(); j++) {
                sparkFileList.add(nodeListSpark.item(j).getTextContent());
            }
        }
    }

    private void newPeer() {
        hashID = createHashID();
    }

    public void close() {
        if (status == PeerStatus.RUNNING) {
            saveData();
        }
    }

    private void saveData() {
        String path = FileUtil.getResourcesPath() + ConfigUtil.getInstance().get("peerDataPath");
        File peerXMLFile = new File(path);
        FileUtil.makeParentFolder(peerXMLFile);
        Document document = XMLUtil.create();

        Element elementPeer = document.createElement("peer");

        // 存储hashID
        elementPeer.setAttribute("hashID", HashIDUtil.toString(hashID));
        document.appendChild(elementPeer);

        // 存储spark列表
        Element elementSparks = document.createElement("sparks");
        for (String sparkFileHashID : sparkFileList) {
            Element elementSpark = document.createElement("spark");
            elementSpark.setTextContent(sparkFileHashID);
            elementSparks.appendChild(elementSpark);
        }
        elementPeer.appendChild(elementSparks);

        // 保存
        XMLUtil.save(document, peerXMLFile);
    }

    public byte[] getHashID() {
        return hashID;
    }

    public String getHashIDString() {
        return HashIDUtil.toString(hashID);
    }

    public RouteList getRouteList() {
        return routeList;
    }

    public List<String> getSparkFileList() {
        return sparkFileList;
    }

    public PeerStatus getStatus() {
        return status;
    }

    public void setStatus(PeerStatus status) {
        this.status = status;
    }

    public static void main(String[] args) {
        // 载入配置文件
        try {
            ConfigUtil.getInstance().load(FileUtil.getResourcesPath() + "configs/config.properties");
        } catch (IOException e) {
            e.printStackTrace();
        }

        Scanner scanner = new Scanner((System.in));

        Peer peer = new Peer();
        peer.joinNetWork(scanner.next());

        String op = scanner.next();;
        while (!op.equals("exit")) {
            peer.makeSpark(op);
            op = scanner.next();
        }
        peer.close();
    }
}
