package com.lzp.cluster.service;

import com.lzp.cluster.client.ClientService;
import com.lzp.common.cache.AutoDeleteMap;
import com.lzp.common.cache.Cache;
import com.lzp.common.cache.LfuCache;
import com.lzp.common.datastructure.queue.NoLockBlockingQueue;
import com.lzp.common.datastructure.set.Zset;
import com.lzp.common.protocol.CommandDTO;
import com.lzp.common.util.FileUtil;
import com.lzp.common.util.HashUtil;
import com.lzp.common.util.SerialUtil;
import com.lzp.singlemachine.service.ConsMesService;
import com.lzp.common.service.PersistenceService;
import com.lzp.common.service.ThreadFactoryImpl;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Description:有一个消息队列的缓存服务，对应一个消费消息的线程。
 *
 * @author: Lu ZePing
 * @date: 2019/7/1 18:13
 */
public class MasterConsMesService {
    private static final NoLockBlockingQueue<MasterConsMesService.Message> QUEUE;

    private static final Cache<String, Object> CACHE;

    private static final Logger logger = LoggerFactory.getLogger(ConsMesService.class);

    private static List<Channel> slaves = new ArrayList<>();

    private static ThreadPoolExecutor heartBeatThreadPool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), new ThreadFactoryImpl("heartBeat"));

    private final static int SNAPSHOT_BATCH_COUNT_D1;

    private static int journalNum = 0;

    public static final int THREAD_NUM;

    static {
        int approHalfCpuCore;
        THREAD_NUM = (approHalfCpuCore = HashUtil.tableSizeFor(Runtime.getRuntime().availableProcessors()) / 2) < 1 ? 1 : approHalfCpuCore;
        int maxSize = Integer.parseInt(FileUtil.getProperty("lruCacheMaxSize"));
        SNAPSHOT_BATCH_COUNT_D1 = Integer.parseInt(FileUtil.getProperty("snapshot-batch-count")) - 1;
        ExecutorService threadPool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1), new ThreadFactoryImpl("operCache"));
        //如果有持久化文件就恢复数据，没有就初始化缓存
        if ("LRU".equals(FileUtil.getProperty("strategy"))) {
            File file = new File("./persistence/corecache/snapshot.ser");
            if (!file.exists()) {
                CACHE = new AutoDeleteMap<>(maxSize);
            } else {
                ObjectInputStream objectInputStream = null;
                try {
                    objectInputStream = new ObjectInputStream(new FileInputStream(file));
                    CACHE = (AutoDeleteMap<String, Object>) objectInputStream.readObject();
                } catch (IOException | ClassNotFoundException e) {
                    logger.error(e.getMessage(), e);
                    throw new RuntimeException();
                } catch (ClassCastException e) {
                    logger.error("持久化文件的缓存淘汰策略和配置文件不一致");
                    throw e;
                } finally {
                    if (objectInputStream != null) {
                        try {
                            objectInputStream.close();
                        } catch (IOException e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
                BufferedReader bufferedReader = null;
                try {
                    bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream("./persistence/corecache/journal.txt"),"UTF-8"));
                    String cmd;
                    bufferedReader.readLine();
                    while ((cmd = bufferedReader.readLine()) != null) {
                        restoreData(cmd.split("ÈÈ"));
                    }
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                    throw new RuntimeException();
                } finally {
                    if (bufferedReader != null) {
                        try {
                            bufferedReader.close();
                        } catch (IOException e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }

            }
        } else {
            CACHE = new LfuCache(maxSize);
        }
        QUEUE = new NoLockBlockingQueue<>(Integer.parseInt(FileUtil.getProperty("queueSize")), THREAD_NUM);
        threadPool.execute(() -> operCache());
        //清空持久化文件，生成一次快照
        PersistenceService.generateSnapshot(CACHE);
        heartBeatThreadPool.execute(MasterConsMesService::heartBeat);
    }

    public static class Message {
        CommandDTO.Command command;
        ChannelHandlerContext channelHandlerContext;

        public Message(CommandDTO.Command command, ChannelHandlerContext channelHandlerContext) {
            this.command = command;
            this.channelHandlerContext = channelHandlerContext;
        }

    }

    private static void heartBeat(){
        while (true) {
            for (Channel channel : slaves) {
                channel.writeAndFlush(CommandDTO.Command.newBuilder().build());
            }
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(),e);
            }
        }
    }

    private static void restoreData(String[] strings){
        switch (strings[0]){
            case "put": {
                CACHE.put(strings[1], strings[2]);
                break;
            }
            case "incr": {
                String afterValue;
                try {
                    afterValue = String.valueOf(Integer.parseInt((String) CACHE.get(strings[1])) + 1);
                    CACHE.put(strings[1], afterValue);
                } catch (Exception e) {
                    break;
                }
                break;
            }
            case "decr": {
                String afterValue ;
                try {
                    afterValue = String.valueOf(Integer.parseInt((String) CACHE.get(strings[1])) - 1);
                    CACHE.put(strings[1], afterValue);
                } catch (Exception e) {
                    break;
                }
                break;
            }
            case "hput": {
                Object value;
                if ((value = CACHE.get(strings[1])) !=null && !(value instanceof Map)){
                    break;
                }
                Map<String,String> values = SerialUtil.stringToMap(strings[2]);
                CACHE.put(strings[1],values);
                break;
            }
            case "hmerge": {
                Object value;
                if ((value = CACHE.get(strings[1])) == null) {
                    Map<String, String> values = SerialUtil.stringToMap(strings[2]);
                    CACHE.put(strings[1], values);
                } else if (!(value instanceof Map)) {
                    break;
                } else {
                    Map<String, String> mapValue = (Map<String, String>) value;
                    Map<String, String> values = SerialUtil.stringToMap(strings[2]);
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        mapValue.put(entry.getKey(), entry.getValue());
                    }
                }
                break;
            }
            case "lpush": {
                Object value;
                if ((value = CACHE.get(strings[1])) == null) {
                    //不values.addAll(Arrays.asList(message.command.getValue().split(","))) 这样写的原因是他底层也是要addAll的，没区别
                    //而且还多了一步new java.util.Arrays.ArrayList()的操作。虽然jvm在编译的时候可能就会优化成和我写的一样，但最终结果都一样，这样写直观一点。下面同样
                    CACHE.put(strings[1], SerialUtil.stringToList(strings[2]));
                } else if (!(value instanceof List)) {
                    break;
                } else {
                    List<String> listValue = (List<String>) value;
                    listValue.addAll(SerialUtil.stringToList(strings[2]));
                }
                break;
            }
            case "sadd": {
                Object value;
                if ((value = CACHE.get(strings[1])) == null) {
                    CACHE.put(strings[1], SerialUtil.stringToSet(strings[2]));
                } else if (!(value instanceof List)) {
                    break;
                } else {
                    Set<String> setValue = (Set<String>) value;
                    setValue.addAll(SerialUtil.stringToList(strings[2]));
                }
                break;
            }
            case "zadd": {
                try {
                    Zset zset = (Zset) CACHE.get(strings[1]);
                    String[] strings1 = (strings[2].split("È"));
                    for (String e : strings1) {
                        String[] scoreMem = e.split("©");
                        zset.zadd(Double.parseDouble(scoreMem[0]), scoreMem[1]);
                    }
                } catch (Exception e) {
                    break;
                }
                break;
            }
            case "remove": {
                CACHE.remove(strings[1]);
                break;
            }
            default:
        }
    }

    private static void operCache() {
        try {
            while (true) {
                MasterConsMesService.Message message = QUEUE.take();
                switch (message.command.getType()) {
                    case "get": {
                        Object retern = CACHE.get(message.command.getKey());
                        String result = retern == null ? "null" : retern.toString();
                        message.channelHandlerContext.writeAndFlush(result.getBytes(StandardCharsets.UTF_8));
                        break;
                    }
                    case "put": {
                        if (((++journalNum) & SNAPSHOT_BATCH_COUNT_D1) == 0) {
                            PersistenceService.generateSnapshot(CACHE);
                        }
                        PersistenceService.writeJournal(message.command);
                        for (Channel channel:slaves){
                            channel.writeAndFlush(message.command);
                        }
                        String key = message.command.getKey();
                        Object preValue;
                        if ((preValue = CACHE.get(key)) instanceof String || preValue == null) {
                            CACHE.put(key, message.command.getValue());
                            message.channelHandlerContext.writeAndFlush(new byte[0]);
                        } else {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    }
                    case "incr": {
                        if (((++journalNum) & SNAPSHOT_BATCH_COUNT_D1) == 0) {
                            PersistenceService.generateSnapshot(CACHE);
                        }
                        PersistenceService.writeJournal(message.command);
                        for (Channel channel:slaves){
                            channel.writeAndFlush(message.command);
                        }
                        String key = message.command.getKey();
                        String afterValue;
                        try {
                            afterValue = String.valueOf(Integer.parseInt((String) CACHE.get(message.command.getKey())) + 1);
                            CACHE.put(key, afterValue);
                        } catch (Exception e) {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                            break;
                        }
                        message.channelHandlerContext.writeAndFlush(afterValue.getBytes(StandardCharsets.UTF_8));
                        break;
                    }
                    case "decr": {
                        if (((++journalNum) & SNAPSHOT_BATCH_COUNT_D1) == 0) {
                            PersistenceService.generateSnapshot(CACHE);
                        }
                        PersistenceService.writeJournal(message.command);
                        for (Channel channel:slaves){
                            channel.writeAndFlush(message.command);
                        }
                        String key = message.command.getKey();
                        String afterValue ;
                        try {
                            afterValue = String.valueOf(Integer.parseInt((String) CACHE.get(message.command.getKey())) - 1);
                            CACHE.put(key, afterValue);
                        } catch (Exception e) {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                            break;
                        }
                        message.channelHandlerContext.writeAndFlush(afterValue.getBytes(StandardCharsets.UTF_8));
                        break;
                    }
                    case "hput": {
                        //写持久化日志
                        if (((++journalNum) & SNAPSHOT_BATCH_COUNT_D1) == 0) {
                            PersistenceService.generateSnapshot(CACHE);
                        }
                        PersistenceService.writeJournal(message.command);
                        for (Channel channel:slaves){
                            channel.writeAndFlush(message.command);
                        }
                        String key = message.command.getKey();
                        Object value;
                        if ((value = CACHE.get(key)) !=null && !(value instanceof Map)){
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                            break;
                        }
                        Map<String,String> values = SerialUtil.stringToMap(message.command.getValue());
                        CACHE.put(key,values);
                        message.channelHandlerContext.writeAndFlush(new byte[0]);
                        break;
                    }
                    case "hmerge": {
                        if (((++journalNum) & SNAPSHOT_BATCH_COUNT_D1) == 0) {
                            PersistenceService.generateSnapshot(CACHE);
                        }
                        PersistenceService.writeJournal(message.command);
                        for (Channel channel:slaves){
                            channel.writeAndFlush(message.command);
                        }
                        String key = message.command.getKey();
                        Object value;
                        if ((value = CACHE.get(key)) == null) {
                            Map<String, String> values = SerialUtil.stringToMap(message.command.getValue());
                            CACHE.put(key, values);
                        } else if (!(value instanceof Map)) {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                            break;
                        } else {
                            Map<String, String> mapValue = (Map<String, String>) value;
                            Map<String, String> values = SerialUtil.stringToMap(message.command.getValue());
                            for (Map.Entry<String, String> entry : values.entrySet()) {
                                mapValue.put(entry.getKey(), entry.getValue());
                            }
                        }
                        message.channelHandlerContext.writeAndFlush(new byte[0]);
                        break;
                    }
                    case "lpush": {
                        if (((++journalNum) & SNAPSHOT_BATCH_COUNT_D1) == 0) {
                            PersistenceService.generateSnapshot(CACHE);
                        }
                        PersistenceService.writeJournal(message.command);
                        for (Channel channel:slaves){
                            channel.writeAndFlush(message.command);
                        }
                        String key = message.command.getKey();
                        Object value;
                        if ((value = CACHE.get(key)) == null) {
                            CACHE.put(key, SerialUtil.stringToList(message.command.getValue()));
                        } else if (!(value instanceof List)) {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                            break;
                        } else {
                            List<String> listValue = (List<String>) value;
                            listValue.addAll(SerialUtil.stringToList(message.command.getValue()));
                        }
                        message.channelHandlerContext.writeAndFlush(new byte[0]);
                        break;
                    }
                    case "sadd": {
                        if (((++journalNum) & SNAPSHOT_BATCH_COUNT_D1) == 0) {
                            PersistenceService.generateSnapshot(CACHE);
                        }
                        PersistenceService.writeJournal(message.command);
                        for (Channel channel:slaves){
                            channel.writeAndFlush(message.command);
                        }
                        String key = message.command.getKey();
                        Object value;
                        if ((value = CACHE.get(key)) == null) {
                            CACHE.put(key, SerialUtil.stringToSet(message.command.getValue()));
                        } else if (!(value instanceof Set)) {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                            break;
                        } else {
                            Set<String> setValue = (Set<String>) value;
                            setValue.addAll(SerialUtil.stringToList(message.command.getValue()));
                        }
                        message.channelHandlerContext.writeAndFlush(new byte[0]);
                        break;
                    }
                    case "zadd": {
                        if (((++journalNum) & SNAPSHOT_BATCH_COUNT_D1) == 0) {
                            PersistenceService.generateSnapshot(CACHE);
                        }
                        PersistenceService.writeJournal(message.command);
                        for (Channel channel:slaves){
                            channel.writeAndFlush(message.command);
                        }
                        String key = message.command.getKey();
                        Object value = CACHE.get(key);
                        if (value == null) {
                            value = new Zset();
                            String[] strings = message.command.getValue().split("È");
                            for (String e : strings) {
                                String[] scoreMem = e.split("©");
                                ((Zset) value).zadd(Double.parseDouble(scoreMem[0]), scoreMem[1]);
                            }
                            CACHE.put(key, value);
                        } else if (value instanceof Zset) {
                            String[] strings = message.command.getValue().split("È");
                            for (String e : strings) {
                                String[] scoreMem = e.split("©");
                                ((Zset) value).zadd(Double.parseDouble(scoreMem[0]), scoreMem[1]);
                            }
                        } else {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                        }
                        message.channelHandlerContext.writeAndFlush(new byte[0]);
                        break;
                    }
                    case "hset": {
                        if (((++journalNum) & SNAPSHOT_BATCH_COUNT_D1) == 0) {
                            PersistenceService.generateSnapshot(CACHE);
                        }
                        PersistenceService.writeJournal(message.command);
                        for (Channel channel:slaves){
                            channel.writeAndFlush(message.command);
                        }
                        String key = message.command.getKey();
                        Object value;
                        if ((value = CACHE.get(key)) == null) {
                            Map<String, String> values = SerialUtil.stringToMap(message.command.getValue());
                            CACHE.put(key, values);
                        } else if (!(value instanceof Map)) {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                            break;
                        } else {
                            Map<String, String> mapValue = (Map<String, String>) value;
                            String[] keyValue = message.command.getValue().split("©");
                            mapValue.put(keyValue[0],keyValue[1]);
                        }
                        message.channelHandlerContext.writeAndFlush(new byte[0]);
                        break;
                    }
                    case "hget": {
                        try {
                            Map<String, String> values = (Map<String, String>) CACHE.get(message.command.getKey());
                            if (values == null) {
                                message.channelHandlerContext.writeAndFlush("null".getBytes(StandardCharsets.UTF_8));
                            } else {
                                String result;
                                message.channelHandlerContext.writeAndFlush((result = values.get(message.command.getValue())) == null ? "null".getBytes(StandardCharsets.UTF_8) : result.getBytes(StandardCharsets.UTF_8));
                            }
                        } catch (Exception e) {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    }
                    case "getList": {
                        try {
                            List<String> values = (List<String>) CACHE.get(message.command.getKey());
                            message.channelHandlerContext.writeAndFlush(values == null ? "null".getBytes(StandardCharsets.UTF_8) : SerialUtil.collectionToString(values).getBytes(StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    }
                    case "getSet": {
                        try {
                            Set<String> values = (Set<String>) CACHE.get(message.command.getKey());
                            message.channelHandlerContext.writeAndFlush(values == null ? "null".getBytes(StandardCharsets.UTF_8) : SerialUtil.collectionToString(values).getBytes(StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    }
                    case "scontain": {
                        try {
                            Set<String> values = (Set<String>) CACHE.get(message.command.getKey());
                            message.channelHandlerContext.writeAndFlush(String.valueOf(values.contains(message.command.getValue())).getBytes(StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    }
                    case "expire": {
                        String key = message.command.getKey();
                        if (CACHE.get(key) == null) {
                            message.channelHandlerContext.writeAndFlush("0".getBytes(StandardCharsets.UTF_8));
                        } else {
                            long expireTime = Instant.now().toEpochMilli() + (Long.parseLong(message.command.getValue()) * 1000);
                            MasterExpireService.setKeyAndTime(key, expireTime);
                            PersistenceService.writeExpireJournal(key +
                                    "ÈÈ" + expireTime);
                            for (Channel channel : slaves) {
                                channel.writeAndFlush(message.command);
                            }
                            message.channelHandlerContext.writeAndFlush("1".getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    }
                    case "remove": {
                        if (((++journalNum) & SNAPSHOT_BATCH_COUNT_D1) == 0) {
                            PersistenceService.generateSnapshot(CACHE);
                        }
                        PersistenceService.writeJournal(message.command);
                        CACHE.remove(message.command.getKey());
                        if (message.channelHandlerContext != null) {
                            for (Channel channel : slaves) {
                                channel.writeAndFlush(message.command);
                            }
                            message.channelHandlerContext.writeAndFlush(new byte[0]);
                        }

                        break;
                    }
                    case "zrange": {
                        try {
                            Zset zset = (Zset) CACHE.get(message.command.getKey());
                            String[] startAndEnd = message.command.getValue().split("©");
                            message.channelHandlerContext.writeAndFlush(zset.zrange(Long.parseLong(startAndEnd[0]), Long.parseLong(startAndEnd[1])).getBytes(StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            message.channelHandlerContext.writeAndFlush("e".getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    }
                    case "zrem": {
                        //todo 本地调用Zset其实都实现了，rpc暂时没时间写，有空补上
                        break;
                    }
                    case "zincrby": {
                        //todo 本地调用Zset其实都实现了，rpc暂时没时间写，有空补上
                        break;
                    }
                    case "zrank": {
                        //todo 本地调用Zset其实都实现了，rpc暂时没时间写，有空补上
                        break;
                    }
                    case "zrevrank": {
                        //todo 本地调用Zset其实都实现了，rpc暂时没时间写，有空补上
                        break;
                    }
                    case "zrevrange": {
                        //todo 本地调用Zset其实都实现了，rpc暂时没时间写，有空补上
                        break;
                    }
                    case "zcard": {
                        //todo 本地调用Zset其实都实现了，rpc暂时没时间写，有空补上
                        break;
                    }
                    case "zscore": {
                        //todo 本地调用Zset其实都实现了，rpc暂时没时间写，有空补上
                        break;
                    }
                    case "zcount": {
                        //todo 本地调用Zset其实都实现了，rpc暂时没时间写，有空补上
                        break;
                    }
                    case "zrangeByScore": {
                        //todo 本地调用Zset其实都实现了，rpc暂时没时间写，有空补上
                        break;
                    }
                    case "fullSync": {
                        connectSlaveAndSendSyncData(message);
                        break;
                    }
                    case "getMaster": {
                        message.channelHandlerContext.writeAndFlush("yes".getBytes(StandardCharsets.UTF_8));
                        break;
                    }
                    default:
                        throw new IllegalStateException("Unexpected value: " + message.command.getType());
                }
            }
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
    }
    /**
     * Description ：和从节点建立长连接并把持久化文件发过去，通知原有从节点去建立长连接。
     *  丢到专门持久化的单线程线程池中执行。等待执行完成再返回。因为必须得保证同步过去的数据是最新的。
     * @Return
     **/
    private static void connectSlaveAndSendSyncData(Message message) {
        try {
            PersistenceService.submitTask(() -> {
                InetSocketAddress inetSocketAddress = (InetSocketAddress) message.channelHandlerContext.channel().remoteAddress();
                String ip = inetSocketAddress.getAddress().getHostAddress();
                int port = Integer.parseInt(message.command.getKey());
                message.channelHandlerContext.channel().close();
                noticeAllSlave(ip, port);
                Channel channel = ClientService.getConnection(ip, port);
                FileInputStream snapshotFileInputStream = null;
                FileInputStream journalFileInputStream = null;
                FileInputStream expireSnapshotFileInputStream = null;
                FileInputStream expireJournalFileInputStream = null;
                try {
                    snapshotFileInputStream = new FileInputStream("./persistence/corecache/snapshot.ser");
                    byte[] snapshotsBytes = new byte[snapshotFileInputStream.available()];
                    snapshotFileInputStream.read(snapshotsBytes);
                    journalFileInputStream = new FileInputStream("./persistence/corecache/journal.txt");
                    byte[] journalBytes = new byte[journalFileInputStream.available()];
                    journalFileInputStream.read(journalBytes);
                    expireSnapshotFileInputStream = new FileInputStream("./persistence/expire/snapshot.ser");
                    byte[] expireSnapshotsBytes = new byte[expireSnapshotFileInputStream.available()];
                    expireSnapshotFileInputStream.read(expireSnapshotsBytes);
                    expireJournalFileInputStream = new FileInputStream("./persistence/expire/journal.txt");
                    byte[] expireJournalBytes = new byte[expireJournalFileInputStream.available()];
                    expireJournalFileInputStream.read(expireJournalBytes);
                    channel.writeAndFlush(CommandDTO.Command.newBuilder().setType("fullSync").setKey(SerialUtil.toHexString(snapshotsBytes) + "■■■■■" + SerialUtil.toHexString(expireSnapshotsBytes))
                            .setValue(SerialUtil.toHexString(journalBytes) + "■■■■■" + SerialUtil.toHexString(expireJournalBytes)).build());
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                } finally {
                    FileUtil.closeResource(snapshotFileInputStream,journalFileInputStream,expireJournalFileInputStream,expireSnapshotFileInputStream);
                }
                slaves.add(channel);
            }).get();
        } catch (Exception e) {
            logger.error(e.getMessage(),e);
        }
    }


    /**
     * Description ：通知已有从节点和新加入从节点建立连接
     *
     * @param ip   新创建的从节点ip
     * @param port 新创建的从节点端口
     * @Return
     **/
    private static void noticeAllSlave(String ip, int port) {
        for (Channel channel : slaves) {
            channel.writeAndFlush(CommandDTO.Command.newBuilder().setType("notice").setKey(ip).setValue(String.valueOf(port)).build());
        }
    }

    public static void addMessage(MasterConsMesService.Message message, int threadId) {
        try {
            QUEUE.put(message, threadId);
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
    }


    public static void setSlaves(List<Channel> slaves) {
        MasterConsMesService.slaves = slaves;
    }

}
