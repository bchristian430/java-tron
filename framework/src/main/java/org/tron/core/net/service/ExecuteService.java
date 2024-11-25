package org.tron.core.net.service;


import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.Wallet;
import org.tron.core.db.Manager;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.message.adv.TransactionMessage;
import org.tron.core.net.messagehandler.ExecuteLog;
import org.tron.core.net.messagehandler.MyLogger;
import org.tron.core.net.messagehandler.TransactionExtension;
import org.tron.core.net.messagehandler.TransanctionType;
import org.tron.core.net.service.adv.AdvService;
import org.tron.trident.abi.FunctionEncoder;
import org.tron.trident.abi.FunctionReturnDecoder;
import org.tron.trident.abi.TypeReference;
import org.tron.trident.abi.datatypes.*;
import org.tron.trident.abi.datatypes.Address;
import org.tron.trident.abi.datatypes.generated.Uint256;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.proto.Chain.Transaction;
import org.tron.trident.proto.Contract;
import org.tron.trident.proto.Response.TransactionExtention;
import org.tron.trident.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.tron.core.net.service.Constant.*;
import static org.tron.trident.abi.Utils.typeMap;
import static org.tron.trident.core.ApiWrapper.parseAddress;

@Slf4j(topic = "net")
@Component
public class ExecuteService {

	@Autowired
	public TronNetDelegate tronNetDelegate;

	@Autowired
	public AdvService advService;

	@Autowired
	public Manager dbManager;

	@Autowired
	public Wallet wallet;

	private final ByteString bsSunSwapRouterAddress = parseAddress(sContractSunSwapRouterAddress);
	private String PK = null;
	private ApiWrapper apiWrapper = null;
	public String sWalletHexAddress = null;
	public String sContractAddress = null;
	private ByteString bsWalletAddress = null;
	private Address addressWallet = null;
	private long trx_min = 0;
	private long trx_max = 0;
	private int count1_min = 0;
	private int count1_max = 0;
	private int count2_min = 0;
	private int count2_max = 0;
	private Uint256 mev_amount = new Uint256(1000000 * 1000);
	private Uint256 limit = new Uint256(10000000);
	private Map<String, String[]> sUrls = new HashMap<>();

	private static ExecuteService _instance = null;

	public static ExecuteService getInstance() {
		if (_instance == null) {
			_instance = new ExecuteService();
		}

		return _instance;
	}

	public void notifyEnvChange() {
		EnvService envService = EnvService.getInstance();
		String newPK = envService.get("PK");
		if (PK == null || !PK.equals(newPK)) {
			if (apiWrapper != null) {
				apiWrapper.close();
			}

			PK = newPK;
			apiWrapper = new ApiWrapper(sUrlGrpcEndpoint, sUrlGrpcSolidityEndpoint, PK);
			sWalletHexAddress = apiWrapper.keyPair.toBase58CheckAddress();
			bsWalletAddress = parseAddress(sWalletHexAddress);
			addressWallet = new Address(sWalletHexAddress);

			balance(sWalletHexAddress);
			long timestamp = System.currentTimeMillis();
			System.out.println(getV1Reservation("TXL6rJbvmjD46zeN1JssfgxvSo99qC8MRT"));
			System.out.println(System.currentTimeMillis() - timestamp);
			timestamp = System.currentTimeMillis();
			System.out.println(getV1Reservation("TAz6oGWhsmHPp7Ap6khmAYxjfHFYokqdQ4"));
			System.out.println(System.currentTimeMillis() - timestamp);
			timestamp = System.currentTimeMillis();
			System.out.println(getV1Reservation("TLcdNJv29Lk4vBT4kRin49if1BeDi18jXE"));
			System.out.println(System.currentTimeMillis() - timestamp);
		}

		trx_min = Long.parseLong(envService.get("TRXMINAMOUNT"));
		trx_max = Long.parseLong(envService.get("TRXMAXAMOUNT"));
		sContractAddress = envService.get("CONTRACT");
		count1_min = Integer.parseInt(envService.get("COUNT1MIN"));
		count1_max = Integer.parseInt(envService.get("COUNT1MAX"));
		count2_min = Integer.parseInt(envService.get("COUNT2MIN"));
		count2_max = Integer.parseInt(envService.get("COUNT2MAX"));
		mev_amount = new Uint256(Integer.parseInt(envService.get("MEV_AMOUNT")));
		limit = new Uint256(Integer.parseInt(envService.get("ARBITRAGE_LIMIT")));
	}

	public void clearApiList() {
		sUrls.clear();
	}

	public void updateApi(String key, String[] value) {
		sUrls.put(key, value);
	}

	public Address getUniswapV1Pair(String tokenA) {
		Address address = new Address(tokenA);

		long timestamp = System.currentTimeMillis();

		Function funcGetExchange = new Function("getExchange", Collections.singletonList(address),
				Collections.singletonList(new TypeReference<Uint256>() {
				}));

		TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress, sContractSunSwapV1FactoryAddress,
				funcGetExchange);

		String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

		List<Type> list = FunctionReturnDecoder.decode(result, funcGetExchange.getOutputParameters());

		Uint256 pairAddress = (Uint256) list.get(0);

		System.out.println(System.currentTimeMillis() - timestamp);

		return new Address(pairAddress);
	}

	public long balance(String sHolderAddress) {
		long timestamp = System.currentTimeMillis();
		long ret = apiWrapper.getAccount(sHolderAddress).getBalance();
		System.out.println(System.currentTimeMillis() - timestamp);
		return ret;
	}

	public Uint256 balanceOf(String sTRC20ContractAddress, String sHolderAddress) {
		try {
			Address address = new Address(sHolderAddress);
			long timestamp = System.currentTimeMillis();
			Function balanceOf = new Function("balanceOf", Collections.singletonList(address),
					Collections.singletonList(new TypeReference<Uint256>() {
					}));

			TransactionExtention txnExt = apiWrapper.constantCall(sHolderAddress,
					sTRC20ContractAddress, balanceOf);
			String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());
			List<Type> list = FunctionReturnDecoder.decode(result, balanceOf.getOutputParameters());

			System.out.println(System.currentTimeMillis() - timestamp);
			return (Uint256) list.get(0);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static class UniswapPool {
		String pair;
		String tokenA;
		String tokenB;
		Uint256 reserve0;
		Uint256 reserve1;
		int type; // V1, V2

		public String toString() {
			return "Pair: " + pair + ", tokenA: " + reserve0 + ", tokenB: " + reserve1 + ", type: " + type;
		}
	}

	private UniswapPool getV1Reservation(String tokenAddress) {
		UniswapPool pool = new UniswapPool();
		pool.tokenA = "";
		pool.tokenB = tokenAddress;
		pool.type = 1;
		pool.pair = getUniswapV1Pair(tokenAddress).toString();
		pool.reserve0 = new Uint256(balance(pool.pair));
		pool.reserve1 = balanceOf(tokenAddress, pool.pair);
		return pool;
	}

	private Uint256 getOutputExpectation(List<String> lstPath, Uint256 lAmountIn) {
		Function funcGetAmountsOut = new Function("getAmountsOut", Arrays.asList(lAmountIn,
				new DynamicArray<>(Address.class, typeMap(lstPath, Address.class))),
				Collections.singletonList(new TypeReference<DynamicArray<Uint256>>() {
				}));

		TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress, sContractSunSwapRouterAddress,
				funcGetAmountsOut);

		String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

		List<Type> list = FunctionReturnDecoder.decode(result, funcGetAmountsOut.getOutputParameters());

		DynamicArray<Uint256> dynamicArray = (DynamicArray<Uint256>) list.get(0);
		List<Uint256> outputList = dynamicArray.getValue();

		return outputList.get(outputList.size() - 1);
	}

	private Contract.TriggerSmartContract createFrontTrigger(String sHexpath1Address, long lAmountIn,
	                                                         Uint256 uint256AmountOut, long lDeadline) {
		Function funcSwapExactETHForTokens = new Function("swapExactETHForTokens", Arrays.asList(uint256AmountOut,
				new DynamicArray<>(Address.class, typeMap(Arrays.asList(sTrc20WtrxAddress, sHexpath1Address), Address.class))
				, addressWallet, new Uint256(lDeadline)), Collections.emptyList());

		String encoded = FunctionEncoder.encode(funcSwapExactETHForTokens);

		Contract.TriggerSmartContract trigger =
				Contract.TriggerSmartContract.newBuilder().setOwnerAddress(bsWalletAddress).setCallValue(lAmountIn).setContractAddress(bsSunSwapRouterAddress).setData(ApiWrapper.parseHex(encoded)).build();

		return trigger;
	}

	private Transaction callAndSignFront(Contract.TriggerSmartContract trigger) {
		TransactionExtention txnExt = apiWrapper.blockingStub.triggerConstantContract(trigger);
		Transaction unsignedTxn =
				txnExt.getTransaction().toBuilder().setRawData(txnExt.getTransaction().getRawData().toBuilder().setFeeLimit(lGasLimit)).build();

		// Sign and return the transaction
		return apiWrapper.signTransaction(unsignedTxn);
	}

	@Async
	protected CompletableFuture<Void>[] buildFrontTransaction(String sHexpath1Address, long lAmountIn,
	                                                          Uint256 uint256AmountOut, long lDeadline, int nCopyCount) {

		// Prepare the function for swapping
		Contract.TriggerSmartContract trigger = createFrontTrigger(sHexpath1Address, lAmountIn, uint256AmountOut,
				lDeadline);

		CompletableFuture<Void>[] futures = new CompletableFuture[nCopyCount];

		// Create and add the CompletableFutures to the array
		for (int i = 0; i < nCopyCount; i++) {
			Transaction trx = callAndSignFront(trigger);
			int finalI = i;
			futures[i] = CompletableFuture.runAsync(() -> {
				TransactionExtension trxExt = new TransactionExtension(trx, TransanctionType.FRONT, finalI);
				if (finalI == 0) {
					BroadcastWithHttpBulk(trxExt);
				}
				BroadcastWithGrpc(trxExt);
			});
		}

		// Return the array of CompletableFutures
		return futures;
	}

	private Function createBackFunction(String sHexpath1Address, Uint256 lAmountIn, Uint256 uint256AmountOut,
	                                    long lDeadline) {
		return new Function("swapExactTokensForETH", Arrays.asList(uint256AmountOut, lAmountIn,
				new DynamicArray<>(Address.class, typeMap(Arrays.asList(sHexpath1Address, sTrc20WtrxAddress), Address.class))
				, addressWallet, new Uint256(lDeadline)), Collections.emptyList());
	}

	private Transaction callAndSignBack(String sContractAddress, Function function) {
		return apiWrapper.signTransaction(apiWrapper.triggerCall(sWalletHexAddress, sContractAddress, function).setFeeLimit(lGasLimit).build());
	}

	@Async
	protected CompletableFuture<Void>[] buildBackTransaction(String sHexpath1Address, long lAmountIn,
	                                                         Uint256 uint256AmountOut, long lDeadline, int nCopyCount) {
		Function funcBack = createBackFunction(sHexpath1Address, new Uint256(lAmountIn), uint256AmountOut, lDeadline);
		CompletableFuture<Void>[] futures = new CompletableFuture[nCopyCount];

		// Create and add the CompletableFutures to the array
		for (int i = 0; i < nCopyCount; i++) {
			Transaction trx = callAndSignBack(sContractSunSwapRouterAddress, funcBack);
			int finalI = i;
			futures[i] = CompletableFuture.runAsync(() -> {
				BroadcastWithGrpc(new TransactionExtension(trx, TransanctionType.BACK, finalI));
			});
		}

		// Return the array of CompletableFutures
		return futures;
	}

	@Async
	protected void BroadcastWithGrpc(TransactionExtension txExt) {
		long timestamp0 = System.currentTimeMillis();
		long timestamp1;
		String resp =  apiWrapper.broadcastTransaction(txExt.transaction);  // Broadcast the transaction
		timestamp1 = System.currentTimeMillis();
		String output = "Type: " + txExt.eType + " Index: " + txExt.nIndex + " GRPC " + timestamp0 + " - " + timestamp1 + " = " + (timestamp1 - timestamp0) + "\n";
		output += "Result: " + resp + "\n";
		MyLogger.print(output);
	}

	public Uint256 balanceOf(String sTrc20ContractAddress) {
		try {
			Function balanceOf = new Function("balanceOf", Collections.singletonList(addressWallet),
					Collections.singletonList(new TypeReference<Uint256>() {
					}));

			TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress,
					sTrc20ContractAddress, balanceOf);
			String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

			List<Type> list = FunctionReturnDecoder.decode(result, balanceOf.getOutputParameters());
			Uint256 balance = (Uint256) list.get(0);

			return balance;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private Uint256 getApproval(String sTrc20ContractAddress) {
		Function funcAllowance = new Function("allowance", Arrays.asList(addressWallet,
				new Address(sContractSunSwapRouterAddress)), Collections.singletonList(new TypeReference<Uint256>() {
		}));

		TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress, sTrc20ContractAddress, funcAllowance);

		String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

		List<Type> list = FunctionReturnDecoder.decode(result, funcAllowance.getOutputParameters());

		return (Uint256) list.get(0);
	}

	private long getLastBlockTimestamp() {
		long timestamp0 = System.currentTimeMillis();

		return timestamp0;
	}

	private void approve(String sTrc20ContractAddress) {
		String hexValue = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
		BigInteger bigInt = new BigInteger(hexValue, 16);

		Function approve = new Function("approve", Arrays.asList(new Address(sContractSunSwapRouterAddress),
				new Uint256(bigInt)), Collections.singletonList(new TypeReference<Uint256>() {
		}));

		Transaction transaction = callAndSignBack(sTrc20ContractAddress, approve);

		BroadcastTransaction(new TransactionExtension(transaction, TransanctionType.APPROVE, 0));
	}

	private void liquidate(String sTrc20ContractAddress, boolean forProfit) {

		Uint256 balanceOf = balanceOf(sTrc20ContractAddress);

		if (balanceOf.getValue().equals(BigInteger.ZERO)) {
			return;
		}

		BigInteger biOutAmount = BigInteger.ONE;
		Long lDeaqdline = System.currentTimeMillis() / 1000L;

		if (forProfit) {
			biOutAmount =
					getOutputExpectation(Arrays.asList(sTrc20ContractAddress, sTrc20WtrxAddress), balanceOf).getValue();
			if (biOutAmount.equals(BigInteger.ZERO)) {
				return;
			}
			biOutAmount.add(BigInteger.ONE);
			lDeaqdline += 5;
		} else {
			lDeaqdline += 1000;
		}

		Function funcBack = createBackFunction(sTrc20ContractAddress, new Uint256(biOutAmount), balanceOf, lDeaqdline);

		// Create and add the CompletableFutures to the array
		Transaction trx = callAndSignBack(sContractSunSwapRouterAddress, funcBack);
		BroadcastTransaction(new TransactionExtension(trx, TransanctionType.LIQUIDATE, 0));
	}

	@Async
	public void execute(String sHexPath1Address, ExecuteLog executeLog) {

		double doubleAmountIn = trx_min + Math.random() * (trx_max - trx_min);
		int nFrontCount = count1_min + (int) (Math.random() * (count1_max - count1_min));
		int nBackCount = count2_min + (int) (Math.random() * (count2_max - count2_min));
		long lAmountIn = (long) (doubleAmountIn * lOneTrx);

//		CompletableFuture.runAsync(() -> {
//			if (getApproval(sHexPath1Address).equals(Uint256.DEFAULT)) {
//				approve(sHexPath1Address);
//			}
//		});

		long lDeadline = System.currentTimeMillis() / 1000 + 5;
		Uint256 uint256OutAmountExpected = getOutputExpectation(Arrays.asList(sTrc20WtrxAddress, sHexPath1Address),
				new Uint256(lAmountIn));

		executeLog.reactTimestamp = System.currentTimeMillis();
		executeLog.reactAmount0 = lAmountIn;
		executeLog.reactAmount1 = uint256OutAmountExpected.getValue();
		executeLog.print();

		buildBackTransaction(sHexPath1Address, (long) (lAmountIn * 0.995), uint256OutAmountExpected, lDeadline,
				nBackCount);
		buildFrontTransaction(sHexPath1Address, lAmountIn, uint256OutAmountExpected, lDeadline, nFrontCount);

		ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
		scheduler.schedule(() -> {
			liquidate(sHexPath1Address, false);
			scheduler.shutdown();
		}, 3, TimeUnit.SECONDS);
	}

//	function expect1(
//			address token,
//			uint flag,
//			uint amount,
//			uint percent,
//			uint inAmount,
//			uint limit)
//	external view returns(
//			address pair0,
//			address pair1,
//			uint inAmountExp,
//			uint outAmount,
//			uint k,
//			uint x
//	)

	@Async
	public void expect1(Address token, Uint256 amount, Uint256 flag, Sha256Hash hashVictim) {

		Uint256 percent = new Uint256(8);

		Function funcExpect = new Function("expect1",
				Arrays.asList(
						token,
						flag,		// 0 : wtrx->token, 1 : token->wtrx, 0 : in expected, 2 : out expected
						amount,	// victim amount
						percent,	// 3 : 0. 8 : 10%
						mev_amount,	// front amount
						limit		// arbitrage limit
				),
				Arrays.asList(
						new TypeReference<Address>() {},	// pair0
						new TypeReference<Address>() {},	// pair1
//						new TypeReference<Uint256>() {},	// inAmountExp
						new TypeReference<Uint256>() {},	// outAmount
						new TypeReference<Uint256>() {},	// k
						new TypeReference<Uint256>() {}		// x
				));

		TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress, sContractAddress, funcExpect);

		ByteString resultString = txnExt.getConstantResult(0);
		if (resultString.size() != 160) {
			return;
		}

		String result = Numeric.toHexString(resultString.toByteArray());

		List<Type> list = FunctionReturnDecoder.decode(result, funcExpect.getOutputParameters());

		Address pair1 = (Address) list.get(0);
		Address pair2 = (Address) list.get(1);
//		inAmount = (Uint256) list.get(2);
		Uint256 outAmount = (Uint256) list.get(2);
		Uint256 k = (Uint256) list.get(3);
		Uint256 x = (Uint256) list.get(4);

		if (!outAmount.equals(Uint256.DEFAULT)) {
			//front and back
			BigInteger in = mev_amount.getValue();
			BigInteger out = outAmount.getValue();
			Function funcFront = new Function("front", Arrays.asList(pair2, token, new Uint256(out.shiftLeft(112).add(in))), Collections.emptyList());

			String encoded = FunctionEncoder.encode(funcFront);

			Contract.TriggerSmartContract trigger =
					Contract.TriggerSmartContract.newBuilder().setOwnerAddress(bsWalletAddress).setCallValue(1).setContractAddress(bsSunSwapRouterAddress).setData(ApiWrapper.parseHex(encoded)).build();

			Transaction trx = callAndSignFront(trigger);
			BroadcastAnkr(trx).thenAccept(txid -> {
				MyLogger.print("Victim Hash : " + hashVictim.toString());
				MyLogger.print("Front Hash : " + txid);
				MyLogger.print("Token : " + token.toString());
			});
			apiWrapper.broadcastTransaction(trx);

			Function funcBack = new Function("back", Arrays.asList(pair2, token,
					new Uint256(in.add(BigInteger.ONE))), Collections.emptyList());

			trx = apiWrapper.signTransaction(apiWrapper.triggerCall(sWalletHexAddress, sContractAddress, funcBack).setFeeLimit(lGasLimit).build());
			BroadcastAnkr(trx).thenAccept(txid -> {
				MyLogger.print("Victim Hash : " + hashVictim.toString());
				MyLogger.print("Back Hash : " + txid);
				MyLogger.print("Token : " + token.toString());
			});
			apiWrapper.broadcastTransaction(trx);

			ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

			scheduler.schedule(() -> {
				if (apiWrapper.getAccount(sContractAddress).getBalance() > 0) {
					Function funcLiquidate = new Function("liquidate", Arrays.asList(pair1, pair2, token), Collections.emptyList());
					Transaction trxLiquidate = apiWrapper.signTransaction(apiWrapper.triggerCall(sWalletHexAddress, sContractAddress, funcLiquidate).setFeeLimit(lGasLimit).build());
					BroadcastAnkr(trxLiquidate).thenAccept(txid -> {
						MyLogger.print("Victim Hash : " + hashVictim.toString());
						MyLogger.print("Liquidate Hash : " + txid);
						MyLogger.print("Token : " + token.toString());
					});
					apiWrapper.broadcastTransaction(trxLiquidate);
				}
				scheduler.shutdown();
			}, 3, TimeUnit.SECONDS);
		}

		if (!k.equals(Uint256.DEFAULT) && !x.equals(Uint256.DEFAULT)) {
			// arbitrage
			Function runFuc = new Function("run1", Arrays.asList(pair1, pair2, token, x), Collections.emptyList());
			Transaction trx = callAndSignBack(sContractAddress, runFuc);
			BroadcastAnkr(trx).thenAccept(txid -> {
				MyLogger.print("Victim Hash : " + hashVictim.toString());
				MyLogger.print("Arbitrage1 Hash : " + txid);
				MyLogger.print("Token : " + token.toString());
			});
		}
	}

	public void expect2(Address token0, Address token1, Uint256 amount, Uint256 flag, Sha256Hash hashVictim) {

		Function funcExpect = new Function("expect2",
				Arrays.asList(
						token0,
						token1,
						limit,		// arbitrage limit
						amount,	// victim amount
						flag		// 0 : wtrx->token, 1 : token->wtrx, 0 : in expected, 2 : out expected
				),
				Arrays.asList(
						new TypeReference<Address>() {},	// pair0
						new TypeReference<Address>() {},	// pair1
						new TypeReference<Address>() {},	// pair2
						new TypeReference<Uint256>() {},	// k
						new TypeReference<Uint256>() {}		// x
				));

		TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress, sContractAddress, funcExpect);

		ByteString resultString = txnExt.getConstantResult(0);
		if (resultString.size() != 160) {
			return;
		}

		String result = Numeric.toHexString(resultString.toByteArray());

		List<Type> list = FunctionReturnDecoder.decode(result, funcExpect.getOutputParameters());

		Address pair0 = (Address) list.get(0);
		Address pair1 = (Address) list.get(1);
		Address pair2 = (Address) list.get(2);
		Uint256 k = (Uint256) list.get(3);
		Uint256 x = (Uint256) list.get(4);

		if (!k.equals(Uint256.DEFAULT) && !x.equals(Uint256.DEFAULT)) {
			// arbitrage
			Function runFuc = new Function("run2", Arrays.asList(pair0, pair1, pair2, token0, token1, x), Collections.emptyList());
			Transaction trx = callAndSignBack(sContractAddress, runFuc);
			BroadcastAnkr(trx).thenAccept(txid -> {
				MyLogger.print("Victim Hash : " + hashVictim.toString());
				MyLogger.print("Arbitrage2 Hash : " + txid);
				MyLogger.print("Token0 : " + token0.toString());
				MyLogger.print("Token1 : " + token1.toString());
			});
		}
	}

	@Async
	public CompletableFuture<String> httpBroadcast(Request request) {
		CompletableFuture<String> future = new CompletableFuture<>();
		OkHttpClient okHttpClient = new OkHttpClient();
		okHttpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
//				System.out.println(request.url() + "\n" + e.getMessage());
//				System.out.print(request.url());
				future.completeExceptionally(e);  // Complete the future exceptionally in case of failure
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException {
				ResponseBody responseBody = response.body();
//				System.out.print(request.url() + "\n" + "success");
				response.close();

				String jsonString = response.toString(); // Get JSON as a string
				Gson gson = new Gson();
				JsonObject jsonObject = gson.fromJson(jsonString, JsonObject.class);

				// Access JSON fields
				String txId = jsonObject.get("txid").getAsString();
				future.complete(txId);  // Complete the future successfully with the response
			}
		});

		return future;
	}

	@Async
	public ArrayList<CompletableFuture<String>> BroadcastWithHttpBulk(TransactionExtension txExt) {
		String sHexRaw = Numeric.toHexString(txExt.transaction.toByteArray());

		ArrayList<CompletableFuture<String>> futures = new ArrayList<>();

		MediaType mediaType = MediaType.parse("application/json");
		RequestBody body = RequestBody.create(mediaType, String.format("{\"transaction\":\"%s\"}", sHexRaw));

		sUrls.forEach((String key, String[] value) -> {

			Request.Builder builder = new Request.Builder();
			builder.method("POST", body);
			builder.addHeader("Content-Type", "application/json");
			builder.addHeader("Accept", "application/json");

			builder.url(value[0] + "/wallet/broadcasthex");
			for (int j = 1; j < value.length; j += 2) {
				builder.addHeader(value[j], value[j + 1]);
			}
			Request request = builder.build();
			CompletableFuture<String> future = httpBroadcast(request);
			long timestamp0 = System.currentTimeMillis();
			future.thenAccept(response -> {
				long timestamp1 = System.currentTimeMillis();
				boolean status = !future.isCompletedExceptionally();
				String output = "Type: " + txExt.eType + " Index: " + txExt.nIndex + " Status: " + status + " " + key + " " + timestamp0 + " - " + timestamp1 + " = " + (timestamp1 - timestamp0) + "\n";
				output += "Response: "+ response + "\n";
				MyLogger.print(output);
			});
			futures.add(future);  // Add each asynchronous httpBroadcast call to the list
		});

		// Combine all futures and wait for them to complete
		return futures;  // Return null after all futures
		// complete
	}

	@Async
	public CompletableFuture<String> BroadcastAnkr(Transaction trx) {
		String sHexRaw = Numeric.toHexString(trx.toByteArray());

		ArrayList<CompletableFuture<String>> futures = new ArrayList<>();

		MediaType mediaType = MediaType.parse("application/json");
		RequestBody body = RequestBody.create(mediaType, String.format("{\"transaction\":\"%s\"}", sHexRaw));

		String[] value = sUrls.get("ANKR");

		Request.Builder builder = new Request.Builder();
		builder.method("POST", body);
		builder.addHeader("Content-Type", "application/json");
		builder.addHeader("Accept", "application/json");

		builder.url(value[0] + "/wallet/broadcasthex");
		Request request = builder.build();

        return httpBroadcast(request);
	}

	@Async
	public ArrayList<CompletableFuture<String>> BroadcastWithHttp(Transaction trx) {
		String sHexRaw = Numeric.toHexString(trx.toByteArray());

		ArrayList<CompletableFuture<String>> futures = new ArrayList<>();

		MediaType mediaType = MediaType.parse("application/json");
		RequestBody body = RequestBody.create(mediaType, String.format("{\"transaction\":\"%s\"}", sHexRaw));

		sUrls.forEach((String key, String[] value) -> {

			Request.Builder builder = new Request.Builder();
			builder.method("POST", body);
			builder.addHeader("Content-Type", "application/json");
			builder.addHeader("Accept", "application/json");

			builder.url(value[0] + "/wallet/broadcasthex");
			for (int j = 1; j < value.length; j += 2) {
				builder.addHeader(value[j], value[j + 1]);
			}
			Request request = builder.build();
			CompletableFuture<String> future = httpBroadcast(request);
			futures.add(future);  // Add each asynchronous httpBroadcast call to the list
		});

		// Combine all futures and wait for them to complete
		return futures;  // Return null after all futures
		// complete
	}

	@Async
	protected ArrayList<CompletableFuture<String>> BroadcastTransaction(TransactionExtension transaction) {
		BroadcastWithGrpc(transaction);
		ArrayList<CompletableFuture<String>> httpBulkFuture = BroadcastWithHttpBulk(transaction);

		return httpBulkFuture;
//		return httpBulkFuture;
//    return CompletableFuture.completedFuture(null);
	}
}
