package org.tron.core.net.service;

public class Constant {
	public static String sTrc20WtrxAddress = "TNUC9Qb1rRpS5CbWLmNMxXBjyFoydXjWFR";
	public static String sContractSunSwapV1FactoryAddress = "TXk8rQSAvPvBBNtqSoY6nCfsXWCSSpTVQF";
	public static String sContractSunSwapV2FactoryAddress = "TKWJdrQkqHisa1X8HUdHEfREvTzw4pMAaY";
	public static String sContractSunSwapV2RouterAddress = "TXF1xDbVGdxFGbovmmmXvBGu8ZiE3Lq4mR";
	public static String sContractSunSwapV2RouterDeprecatedAddress = "TKzxdSv2FZKQrEqkKVgp5DcwEXBEKMg2Ax";
	public static String sContractSunSwapRouterAddress = "TZFs5ch1R1C4mmjwrrmZqeqbUgGpxY1yWB";
	public static long lGasLimit = 50000000L;
	public static String sUrlGrpcEndpoint = "127.0.0.1:50051";
	public static String sUrlGrpcSolidityEndpoint = "127.0.0.1:50051";
	public static int nTrxDecimals = 6;
	public static long lOneTrx = (long) Math.pow(10, nTrxDecimals);
}