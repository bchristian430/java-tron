package org.tron.core.net.messagehandler;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.*;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StringUtil;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.exception.P2pException;
import org.tron.core.exception.P2pException.TypeEnum;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.message.TronMessage;
import org.tron.core.net.message.adv.TransactionMessage;
import org.tron.core.net.message.adv.TransactionsMessage;
import org.tron.core.net.peer.Item;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.service.Constant;
import org.tron.core.net.service.EnvService;
import org.tron.core.net.service.ExecuteService;
import org.tron.core.net.service.TronAsyncService;
import org.tron.core.net.service.adv.AdvService;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Inventory.InventoryType;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;

import static org.tron.core.net.messagehandler.TransactionsMsgHandler.UniSwapV2MethodBytes.*;
import static org.tron.core.net.service.Constant.*;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.convertToTronAddress;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.encode58Check;

import org.tron.protos.contract.SmartContractOuterClass;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;
import org.tron.trident.abi.datatypes.Address;
import org.tron.trident.abi.datatypes.generated.Uint256;
import org.tron.trident.core.ApiWrapper;

import java.util.concurrent.CompletableFuture;

@Slf4j(topic = "net")
@Component
public class TransactionsMsgHandler implements TronMsgHandler {

    private static int MAX_TRX_SIZE = 50_000;
    private static int MAX_SMART_CONTRACT_SUBMIT_SIZE = 100;

    @Autowired
    private TronNetDelegate tronNetDelegate;

    @Autowired
    private AdvService advService;

    @Autowired
    Manager dbManager;

    ExecuteService executeService;

    @Autowired
    private EnvService envService;

    private BlockingQueue<TrxEvent> smartContractQueue = new LinkedBlockingQueue(MAX_TRX_SIZE);

    private BlockingQueue<Runnable> queue = new LinkedBlockingQueue();

    private int threadNum = Args.getInstance().getValidateSignThreadNum();
    private final String trxEsName = "trx-msg-handler";
    private ExecutorService trxHandlePool = ExecutorServiceManager.newThreadPoolExecutor(threadNum, threadNum, 0L,
            TimeUnit.MILLISECONDS, queue, trxEsName);
    private final String smartEsName = "contract-msg-handler";
    private final ScheduledExecutorService smartContractExecutor =
            ExecutorServiceManager.newSingleThreadScheduledExecutor(smartEsName);

//	static class transactionLog {
//		public String key1;
//		public ArrayMap<String, Integer> logs;
//
//		transactionLog(String key) {
//			key1 = key;
//			logs = new ArrayMap<>(0);
//		}
//
//		public transactionLog add(String key2) {
//			if (logs.get(key2) == null) logs.put(key2, 1);
//			else logs.put(key2, logs.get(key2) + 1);
//
//			return this;
//		}
//	}
//
//	ArrayMap<String, transactionLog> transactionLogs = new ArrayMap<>(0);
//	ArrayMap<String, transactionLog> peerLogs = new ArrayMap<>(0);

    public void init() {
        handleSmartContract();

        if (envService == null) {
            envService = EnvService.getInstance();
        }

        if (executeService == null) {
            executeService = ExecuteService.getInstance();
        }
    }

    public void close() {
        ExecutorServiceManager.shutdownAndAwaitTermination(trxHandlePool, trxEsName);
        ExecutorServiceManager.shutdownAndAwaitTermination(smartContractExecutor, smartEsName);
    }

    public boolean isBusy() {
        return queue.size() + smartContractQueue.size() > MAX_TRX_SIZE;
    }

    @Override
    public void processMessage(PeerConnection peer, TronMessage msg) throws P2pException {
        TransactionsMessage transactionsMessage = (TransactionsMessage) msg;
        check(peer, transactionsMessage);
        int smartContractQueueSize = 0;
        int trxHandlePoolQueueSize = 0;
        int dropSmartContractCount = 0;
        for (Transaction trx : transactionsMessage.getTransactions().getTransactionsList()) {
            int type = trx.getRawData().getContract(0).getType().getNumber();
            if (type == ContractType.TriggerSmartContract_VALUE || type == ContractType.CreateSmartContract_VALUE) {
                if (!smartContractQueue.offer(new TrxEvent(peer, new TransactionMessage(trx)))) {
                    smartContractQueueSize = smartContractQueue.size();
                    trxHandlePoolQueueSize = queue.size();
                    dropSmartContractCount++;
                }
            } else {
                trxHandlePool.submit(() -> handleTransaction(peer, new TransactionMessage(trx)));
            }
        }

        if (dropSmartContractCount > 0) {
            logger.warn("Add smart contract failed, drop count: {}, queueSize {}:{}", dropSmartContractCount,
                    smartContractQueueSize, trxHandlePoolQueueSize);
        }
    }

    private void check(PeerConnection peer, TransactionsMessage msg) throws P2pException {
        for (Transaction trx : msg.getTransactions().getTransactionsList()) {
            Item item = new Item(new TransactionMessage(trx).getMessageId(), InventoryType.TRX);
            if (!peer.getAdvInvRequest().containsKey(item)) {
                throw new P2pException(TypeEnum.BAD_MESSAGE, "trx: " + msg.getMessageId() + " without request.");
            }
            peer.getAdvInvRequest().remove(item);
        }
    }

    @Autowired
    private TronAsyncService tronAsyncService;

    private boolean isUniswapV2Router(String address) {
        return address.equals("TKzxdSv2FZKQrEqkKVgp5DcwEXBEKMg2Ax") ||
                address.equals("TXF1xDbVGdxFGbovmmmXvBGu8ZiE3Lq4mR") ||
                address.equals("TZFs5ch1R1C4mmjwrrmZqeqbUgGpxY1yWB");
    }

    private boolean isBlockedAccount(String address) {
        return address.equals(executeService.sWalletHexAddress) || envService.get("BLACKLIST").contains(address);
    }

    @Getter
    public enum UniSwapV2MethodBytes {
        // Enum constants with descriptions and corresponding integer values
        SwapETHForExactTokens(bytesToInt((byte) 0xfb, (byte) 0x3b, (byte) 0xdb, (byte) 0x41)),
        SwapExactETHForTokens(bytesToInt((byte) 0x7f, (byte) 0xf3, (byte) 0x6a, (byte) 0xb5)),
        SwapExactETHForTokensSupportingFeeOnTransferTokens(bytesToInt((byte) 0xb6, (byte) 0xf9, (byte) 0xde, (byte) 0x95)),
        SwapExactTokensForETH(bytesToInt((byte) 0x18, (byte) 0xcb, (byte) 0xaf, (byte) 0xe5)),
        SwapExactTokensForETHSupportingFeeOnTransferTokens(bytesToInt((byte) 0x79, (byte) 0x1a, (byte) 0xc9, (byte) 0x47)),
        SwapExactTokensForTokens(bytesToInt((byte) 0x38, (byte) 0xed, (byte) 0x17, (byte) 0x39)),
        SwapExactTokensForTokensSupportingFeeOnTransferTokens(bytesToInt((byte) 0x5c, (byte) 0x11, (byte) 0xd7,
                (byte) 0x95)),
        SwapTokensForExactETH(bytesToInt((byte) 0x4a, (byte) 0x25, (byte) 0xd9, (byte) 0x4a)),
        SwapTokensForExactTokens(bytesToInt((byte) 0x88, (byte) 0x03, (byte) 0xdb, (byte) 0xee));

        private final int value;

        UniSwapV2MethodBytes(int value) {
            this.value = value;
        }

        // Helper method to convert 4 bytes into an int
        private static int bytesToInt(byte b1, byte b2, byte b3, byte b4) {
            return ((b1 & 0xFF) << 24) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 8) | (b4 & 0xFF);
        }

        // Static method to find the enum constant by integer value
        public static UniSwapV2MethodBytes fromValue(int value) {
            for (UniSwapV2MethodBytes mb : UniSwapV2MethodBytes.values()) {
                if (mb.getValue() == value) {
                    return mb;
                }
            }
            throw new IllegalArgumentException("No enum constant with value: " + Integer.toHexString(value));
        }
    }

    private BigInteger bytesToBigInteger(byte[] bytes) {
        return new BigInteger(1, bytes);
    }

//    private String getV1PairAddress(String tokenAddress) {
//        String factoryAddress = "TXk8rQSAvPvBBNtqSoY6nCfsXWCSSpTVQF";
//
//        return factoryAddress;
//    }
//
//    private String getV2PairAddress(String tokenAAddress, String tokenBAddress) {
//        String factoryAddress = "TKWJdrQkqHisa1X8HUdHEfREvTzw4pMAaY";
//        return factoryAddress;
//    }

    private void checkFunction(TransactionMessage trx) {

        TriggerSmartContract triggerSmartContract = null;

        try {
            Transaction transaction = null;
            transaction = Transaction.parseFrom(trx.getTransactionCapsule().getData());
            if (transaction.getRawData().getContractCount() == 0) {
                return;
            }
            Transaction.Contract contract = transaction.getRawData().getContract(0);
            if (contract.getType() != ContractType.TriggerSmartContract) {
                return;
            }
            triggerSmartContract = contract.getParameter().unpack(TriggerSmartContract.class);

        } catch (InvalidProtocolBufferException e) {
            return;
        }

        String contractAddress = StringUtil.encode58Check(triggerSmartContract.getContractAddress().toByteArray());

        if (!isUniswapV2Router(contractAddress)) {
            return;
        }

        byte[] data = triggerSmartContract.getData().toByteArray();

//        byte[] method = Arrays.copyOfRange(data, 0, 4);
        UniSwapV2MethodBytes methodInt;
        try {
            methodInt = UniSwapV2MethodBytes.fromValue(UniSwapV2MethodBytes.bytesToInt(data[0], data[1], data[2], data[3]));
        } catch (IllegalArgumentException e) {
            return;
        }

        BigInteger amount;
//        BigInteger amount1;
        int offset = 0x24;
        int flag = (methodInt == SwapETHForExactTokens || methodInt == SwapTokensForExactETH || methodInt == SwapTokensForExactTokens) ? 2 : 0;

        if (methodInt == SwapETHForExactTokens) {
            amount = bytesToBigInteger(Arrays.copyOfRange(data, 4, 4 + 0x20));
//            amount1 = BigInteger.valueOf(triggerSmartContract.getCallValue());
            offset = 0x24;
        } else if (methodInt == SwapExactETHForTokens || methodInt == SwapExactETHForTokensSupportingFeeOnTransferTokens) {
            amount = BigInteger.valueOf(triggerSmartContract.getCallValue());
//            amount1 = bytesToBigInteger(Arrays.copyOfRange(data, 4, 4 + 0x20));
            offset = 0x24;
        } else if (methodInt == SwapTokensForExactTokens) {
//            amount0 = bytesToBigInteger(Arrays.copyOfRange(data, 4, 4 + 0x20));
            amount = bytesToBigInteger(Arrays.copyOfRange(data, 4 + 0x20, 4 + 0x40));
            offset = 0x44;
        } else {
            amount = bytesToBigInteger(Arrays.copyOfRange(data, 4, 4 + 0x20));
//            amount1 = bytesToBigInteger(Arrays.copyOfRange(data, 4 + 0x20, 4 + 0x40));
            offset = 0x44;
        }

        offset = ByteBuffer.wrap(data, offset + 0x1C, 4).getInt();
        byte[] bytesToAddress = Arrays.copyOfRange(data, offset + 0x24, offset + 0x44);
        String toAddress = encode58Check(convertToTronAddress(bytesToAddress));

        if (isBlockedAccount(toAddress)) {
            return;
        }

        int pathLen = ByteBuffer.wrap(data, offset + 0x20, 4).getInt();

        byte[] bytesPath0 = Arrays.copyOfRange(data, offset + 0x24, offset + 0x44);
        byte[] bytesPath1 = Arrays.copyOfRange(data, offset + pathLen * 0x20 + 0x4,
                offset + pathLen * 0x20 + 0x24);

        String toPath0 = encode58Check(convertToTronAddress(bytesPath0));
        String toPath1 = encode58Check(convertToTronAddress(bytesPath1));

        if (pathLen != 2) {
            return;
        }

        Sha256Hash hashVictim = trx.getTransactionCapsule().getTransactionId();

        if (toPath0.equals(Constant.sTrc20WtrxAddress)) {
            executeService.expect1(new Address(toPath1), new Uint256(amount), new Uint256(flag), hashVictim);
        } else if (toPath1.equals(Constant.sTrc20WtrxAddress)) {
            flag |= 1;
            executeService.expect1(new Address(toPath0), new Uint256(amount), new Uint256(flag), hashVictim);
        } else {
            executeService.expect2(new Address(toPath1), new Address(toPath0), new Uint256(amount), new Uint256(flag), hashVictim);
        }
    }

    private void handleChance(PeerConnection peer, TransactionMessage trx) {
//        long timestamp = trx.getTransactionCapsule().getTimestamp();
//        long now = System.currentTimeMillis();
        checkFunction(trx);
//        try {
//            Transaction transaction = Transaction.parseFrom(trx.getTransactionCapsule().getData());
//            if (transaction.getRawData().getContractCount() > 0) {
//                Transaction.Contract contract = transaction.getRawData().getContract(0);
//                if (contract.getType() == ContractType.TriggerSmartContract) {
//                    TriggerSmartContract triggerSmartContract =
//                            contract.getParameter().unpack(TriggerSmartContract.class);
//                    checkFunction(triggerSmartContract, trx.getTransactionCapsule().getTransactionId());
//                    String contractAddress = StringUtil.encode58Check(triggerSmartContract.getContractAddress().toByteArray());
//
//                    if (contractAddress.equals("TZFs5ch1R1C4mmjwrrmZqeqbUgGpxY1yWB")) {
//                        byte[] data = triggerSmartContract.getData().toByteArray();
//                        byte[] method = Arrays.copyOfRange(data, 0, 4);
//
//                        long amountIn = 0L;
//                        BigInteger amountOutMin = BigInteger.valueOf(0);
//                        byte[] bytesPath0 = null;
//                        byte[] bytesPath1 = null;
//                        byte[] bytesToAddress = null;
//                        long deadline = 0L;
//
//                        if (Arrays.equals(method, new byte[]{(byte) 0x18, (byte) 0xcb, (byte) 0xaf, (byte) 0xe5})) {
//                            // token -> trx
//                        }
//
//                        if (Arrays.equals(method, new byte[]{(byte) 0x38, (byte) 0xed, (byte) 0x17, (byte) 0x39})) {
//                            // token (WTRX) -> token
//
////							swapExactTokensForTokens(uint256 amountIn, uint256 amountOutMin, address[] path, address to, uint256
////							deadline)
////							MethodID:    38ed1739
////									[0]:   000000000000000000000000000000000000000000000000000000024605ae84
////									[1]:   000000000000000000000000000000000000000000000156335d57f186700000
////									[2]:   00000000000000000000000000000000000000000000000000000000000000a0
////									[3]:   00000000000000000000000063662d8a3e812969b4e716c598c35e0c2c1d4368
////									[4]:   00000000000000000000000000000000000000000000000000000000670d6836
////									[5]:   0000000000000000000000000000000000000000000000000000000000000002
////									[6]:   000000000000000000000000891cdb91d149f23b1a45d9c5ca78a88d0cb44c18
////									[7]:   000000000000000000000000ea4e3dfacdf6d5b25f74ce4b689d79105043583c
//                            byte[] bytesAmountIn = Arrays.copyOfRange(data, 4, 4 + 0x20);
//                            byte[] bytesAmountOut = Arrays.copyOfRange(data, 4 + 0x20, 4 + 0x40);
//                            byte[] bytesOffset = Arrays.copyOfRange(data, 4 + 0x40, 4 + 0x60);
//                            bytesToAddress = Arrays.copyOfRange(data, 4 + 0x60, 4 + 0x80);
//                            byte[] bytesDeadline = Arrays.copyOfRange(data, 4 + 0x80, 4 + 0xA0);
//
//                            int offset = ByteBuffer.wrap(bytesOffset, bytesOffset.length - 4, 4).getInt();
//                            byte[] bytesCount = Arrays.copyOfRange(data, 4 + offset, 4 + offset + 0x20);
//
//                            int arraylength = ByteBuffer.wrap(bytesCount, bytesCount.length - 4, 4).getInt();
//
//                            bytesPath0 = Arrays.copyOfRange(data, 4 + offset + 0x20, 4 + offset + 0x20 * 2);
//                            bytesPath1 = Arrays.copyOfRange(data, 4 + offset + arraylength * 0x20,
//                                    4 + offset + arraylength * 0x20 + 0x20);
//
//                            amountIn = new BigInteger(1, bytesAmountIn).longValue();
//                            amountOutMin = new BigInteger(1, bytesAmountOut);
//                            deadline = new BigInteger(1, bytesDeadline).longValue();
//                        }
//
//                        if (Arrays.equals(method, new byte[]{(byte) 0xfb, (byte) 0x3b, (byte) 0xdb, (byte) 0x41})) {
//                            // trx -> token 0xfb3bdb41
//
//
////							swapETHForExactTokens(uint256 amountOut, address[] path, address to, uint256 deadline)
////							MethodID:    fb3bdb41
////									[0]:   0000000000000000000000000000000000000000000009bd4f5afc71ae680000
////									[1]:   0000000000000000000000000000000000000000000000000000000000000080
////									[2]:   0000000000000000000000008cf67cc2fbf0267f92550d70e248c134fdae42bd
////									[3]:   00000000000000000000000000000000000000000000000000000000670d8107
////									[4]:   0000000000000000000000000000000000000000000000000000000000000002
////									[5]:   000000000000000000000000891cdb91d149f23b1a45d9c5ca78a88d0cb44c18
////									[6]:   000000000000000000000000a8206c1fda9ed9c73e787ea1da2ac75a354df2e1
//
//                            byte[] bytesAmountOut = Arrays.copyOfRange(data, 4, 4 + 0x20);
//                            byte[] bytesOffset = Arrays.copyOfRange(data, 4 + 0x20, 4 + 0x40);
//                            bytesToAddress = Arrays.copyOfRange(data, 4 + 0x40, 4 + 0x60);
//                            byte[] bytesDeadline = Arrays.copyOfRange(data, 4 + 0x60, 4 + 0x80);
//
//                            int offset = ByteBuffer.wrap(bytesOffset, bytesOffset.length - 4, 4).getInt();
//                            byte[] bytesCount = Arrays.copyOfRange(data, 4 + offset, 4 + offset + 0x20);
//
//                            int arraylength = ByteBuffer.wrap(bytesCount, bytesCount.length - 4, 4).getInt();
//                            bytesPath0 = Arrays.copyOfRange(data, 4 + offset + 0x20, 4 + offset + 0x20 * 2);
//                            bytesPath1 = Arrays.copyOfRange(data, 4 + offset + arraylength * 0x20,
//                                    4 + offset + arraylength * 0x20 + 0x20);
//
//                            amountIn = triggerSmartContract.getCallValue();
//                            amountOutMin = new BigInteger(1, bytesAmountOut);
//                        }
//
//                        if (bytesToAddress == null || bytesPath0 == null || bytesPath1 == null) {
//                            return;
//                        }
//
//                        if (amountIn < Long.parseLong(envService.get("AMOUNTINLIMIT")) * lOneTrx) return;
//
//                        String toAddress = encode58Check(convertToTronAddress(bytesToAddress));
//                        String toPath0 = encode58Check(convertToTronAddress(bytesPath0));
//                        String toPath1 = encode58Check(convertToTronAddress(bytesPath1));
//
//                        if (!toPath0.equals(Constant.sTrc20WtrxAddress)) {
//                            // not WTRX
//                            return;
//                        }
//
//                        if (envService.get("BLACKLIST").contains(toAddress) || toAddress.equals(executeService.sWalletHexAddress)) {
//                            return;
//                        }
//
//                        if (!envService.get("APPROVED").contains(toPath1)) {
//                            return;
//                        }
//
//                        ExecuteLog log = new ExecuteLog();
//
//                        log.peerIpAddress = peer.getInetAddress().getHostAddress();
//                        log.peerStatus = peer.getChannel().isActive();
//                        log.vitimHash = trx.getTransactionCapsule().getTransactionId().toString();
//                        log.vitimTimestamp = timestamp;
//                        log.receivedTimestamp = now;
//                        log.vitimAmount0 = amountIn;
//                        log.vitimAmount1 = amountOutMin;
//                        log.vitimAddress = toAddress;
//                        log.tokenAddress = toPath1;
//
////						executeService.execute(toPath1, log);
//
//////						ArrayList<String> blacklist = new ArrayList<>(
//////								Arrays.asList("TPsUGKAoXDSFz332ZYtTGdDHWzftLYWFj7",
//////										"TEtPcNXwPj1PEdsDRCZfUvdFHASrJsFeW5",
//////										"TN2EQwZpKE5UrShg11kHGyRth7LF5GbRPC",
//////										"TJf7YitKX2QU5M2kW9hmcdjrAbEz4T5NyQ",
//////										"TXtARmXejKjroz51YJVkFcdciun8YcU9nn",
//////										"TLJuomNsHx76vLosaW3Tz3MFTqCANL8v5m",
//////										"TSMEzJhS5vrWqy9VNLcRjjNuzrMqnRcMbQ",
//////										"TPrfuW64cDjdC8qYHoujWqy8AbimM5u9bB"));
//////
//////						if (blacklist.contains(toAddress)) {
//////							// blacklist
//////
//////							return;
//////						}
////
////						String inetSocketAddress = peer.getInetSocketAddress().toString();
////
////						if (transactionLogs.get(toAddress) == null) {
////							transactionLogs.put(toAddress, new transactionLog(toAddress));
////						}
////						transactionLogs.put(toAddress, transactionLogs.get(toAddress).add(inetSocketAddress));
////
////						if (peerLogs.get(inetSocketAddress) == null) {
////							peerLogs.put(inetSocketAddress, new transactionLog(inetSocketAddress));
////						}
////						peerLogs.put(inetSocketAddress, peerLogs.get(inetSocketAddress).add(toAddress));
//
////						myLogger.info(String.format("%d - %d = %d\nFrom %s %s %d TRX -> %s token %s%n", now,
////								timestamp, now - timestamp, peer.getInetSocketAddress(), toAddress, amountIn,
////								amountOutMin, toPath1));
////
////						System.out.printf("%d - %d = %d\nFrom %s %s %d TRX -> %s token %s%n", now, timestamp,
////								now - timestamp, peer.getInetSocketAddress(), toAddress, amountIn,
////								amountOutMin, toPath1);
////						try {
//////                                long trx_amount = (long) (Math.random() * 100);
////							long trx_min = Long.parseLong(envService.get("TRXMINAMOUNT"));
////							long trx_max = Long.parseLong(envService.get("TRXMAXAMOUNT"));
////							int count1_min = Integer.parseInt(envService.get("COUNT1MIN"));
////							int count1_max = Integer.parseInt(envService.get("COUNT1MAX"));
////							int count2_min = Integer.parseInt(envService.get("COUNT2MIN"));
////							int count2_max = Integer.parseInt(envService.get("COUNT2MAX"));
////
////							double trx_amount = trx_min + Math.random() * (trx_max - trx_min);
////							long amount = (long) (trx_amount * 1000000L);
////
////							if (!envService.get("APPROVED").contains(toPath1)) {
////								return;
////							}
////
////							CompletableFuture<BigInteger> amountOutFuture = tronAsyncService.getAmountOut(new Uint256(amount),
////									Arrays.asList(WTRX_Address, toPath1));
////							amountOutFuture.thenAccept(amountOut -> {
//////                                    System.out.println("Amount out: " + amountOut);
//////                                    CompletableFuture<Void> approveFuture = tronAsyncService.approve(meme_contract);
//////                                    approveFuture.thenRun(() -> {
//////                                        CompletableFuture<Void> swapTokensFuture = tronAsyncService
//////                                        .swapExactETHForTokens(amount, amountOut, meme_contract);
//////                                        swapTokensFuture.join();  // Wait for the swap to complete
//////                                        System.out.println("Swap completed!");
//////                                    });
////
////								long timedifflimit = Long.parseLong(envService.get("TIMEDIFFLIMIT"));
////								System.out.println(System.currentTimeMillis() - timestamp);
////								if (System.currentTimeMillis() - timestamp > 0 && System.currentTimeMillis() - timestamp <
////								timedifflimit) {
////									System.out.println("Run bot -- " + trx.getTransactionCapsule().getTransactionId());
////									long new_deadline = (int) (System.currentTimeMillis() / 1000) + 5;
////									int count1 = count1_min + (int) (Math.random() * (count1_max - count1_min));
////									int count2 = count2_min + (int) (Math.random() * (count2_max - count2_min));
////
////									for (int i = 0; i < count1; i++) {
////										tronAsyncService.swapExactETHForTokens(amount, amountOut.add(new BigInteger("1")), toPath1,
////												new_deadline);
////									}
////									for (int i = 0; i < count2; i++) {
////										tronAsyncService.swapExactTokensForETH(BigInteger.valueOf((long)(amount * 0.994 + 1)),
////										amountOut, toPath1, new_deadline);
////									}
////
////									ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
////
////									scheduler.schedule(() -> {
////										liquidate(toPath1, true);
////										scheduler.shutdown();
////									}, 3, TimeUnit.SECONDS);
////								}
////
////								liquidate(toPath1, false);
////							}).exceptionally(ex -> {
////								System.err.println("Error occurred: " + ex.getMessage());
////								return null;
////							});
////
////						} catch (Exception ignored) {
////						}
////                            System.out.println(getAmountOut(100 * 1000000L, toPath1));
//                    }
//                }
//            }
//        } catch (Exception e) {
//            System.out.println(e.getMessage());
//        }
    }

    private void liquidate(String meme_contract, boolean forProfit) {

        CompletableFuture<Uint256> balanceOfFuture = tronAsyncService.balanceOf(meme_contract);
        balanceOfFuture.thenAccept(balanceOf -> {

            if (!balanceOf.getValue().equals(BigInteger.ZERO)) {
                CompletableFuture<BigInteger> memeAmountOutFuture = tronAsyncService.getAmountOut(balanceOf,
                        Arrays.asList(meme_contract, sTrc20WtrxAddress));

                memeAmountOutFuture.thenAccept(amountOut -> {
                    if (amountOut.equals(BigInteger.ZERO)) {
                        return;
                    }
                    tronAsyncService.swapExactTokensForETH((forProfit ? amountOut : BigInteger.ZERO).add(new BigInteger("1")),
                            balanceOf.getValue(), meme_contract, (long) (System.currentTimeMillis() / 1000.0 + 6));
                });
            }
        });
    }

    private void handleSmartContract() {
        smartContractExecutor.scheduleWithFixedDelay(() -> {
            try {
                while (queue.size() < MAX_SMART_CONTRACT_SUBMIT_SIZE && !smartContractQueue.isEmpty()) {
                    TrxEvent event = smartContractQueue.take();
                    trxHandlePool.submit(() -> handleTransaction(event.getPeer(), event.getMsg()));
                }
            } catch (InterruptedException e) {
                logger.warn("Handle smart server interrupted");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("Handle smart contract exception", e);
            }
        }, 1000, 20, TimeUnit.MILLISECONDS);
    }

    private void handleTransaction(PeerConnection peer, TransactionMessage trx) {
        if (peer.isBadPeer()) {
            logger.warn("Drop trx {} from {}, peer is bad peer", trx.getMessageId(), peer.getInetAddress());
            return;
        }

        if (advService.getMessage(new Item(trx.getMessageId(), InventoryType.TRX)) != null) {
            return;
        }
        checkFunction(trx);
//        handleChance(peer, trx);

//    try {
//      tronNetDelegate.pushTransaction(trx.getTransactionCapsule());
//      advService.broadcast(trx);
//    } catch (P2pException e) {
//      logger.warn("Trx {} from peer {} process failed. type: {}, reason: {}",
//          trx.getMessageId(), peer.getInetAddress(), e.getType(), e.getMessage());
//      if (e.getType().equals(TypeEnum.BAD_TRX)) {
//        peer.setBadPeer(true);
//        peer.disconnect(ReasonCode.BAD_TX);
//      }
//    } catch (Exception e) {
//      logger.error("Trx {} from peer {} process failed", trx.getMessageId(), peer.getInetAddress(),
//          e);
//    }
    }

    class TrxEvent {

        @Getter
        private PeerConnection peer;
        @Getter
        private TransactionMessage msg;
        @Getter
        private long time;

        public TrxEvent(PeerConnection peer, TransactionMessage msg) {
            this.peer = peer;
            this.msg = msg;
            this.time = System.currentTimeMillis();
        }
    }
}