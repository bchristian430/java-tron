package org.tron.core.net.messagehandler;

import org.tron.core.net.service.Constant;

import java.math.BigInteger;

; // 1: front, 2: back, 3: liquidate, 4: approve

public class ExecuteLog {

//	class HTTPLog {
//		double timestamp0;
//		double timestamp1;
//		String txHash;
//		String name;
//		int index;
//		TransanctionType type;
//		int status;
//		String responseMessage;
//	}

	public String peerIpAddress;
	public boolean peerStatus;

	public String vitimHash;
	public double vitimTimestamp;
	public double reactTimestamp;
	public double vitimAmount0;
	public BigInteger vitimAmount1;
	public String vitimAddress;
	public String tokenAddress;
	public double receivedTimestamp;
	public double reactAmount0;
	public BigInteger reactAmount1;
//	public HTTPLog[] httpLog;
//
//	public void saveLog() {
//	}

	public void print() {
		String output = "";
		output += "New Opportunity" + "\n";
		output += "IP: " + peerIpAddress + " Active?:" + peerStatus + "\n";
		output += "Hash : " + vitimHash + " Timestamp : " + vitimTimestamp + "\n";
		output += "From " + vitimAddress + " TRX : " + (vitimAmount0 / Constant.lOneTrx) + " -> " + vitimAmount1 + " " + tokenAddress + "\n";
		output += "Received Timestamp : " + receivedTimestamp + " Offset : " + (receivedTimestamp - vitimTimestamp) + "\n";
		output += "Reaction Timestamp : " + reactTimestamp + " Offset : " + (reactTimestamp - vitimTimestamp) + " , " + (reactTimestamp - receivedTimestamp) + "\n";
		output += "Reaction Amount : " + reactAmount0 + " -> " + reactAmount1 + "\n";

		printString(output);
	}

	public static void printString(String string) {
		System.out.print(string);
		MyLogger.getInstance().print(string);
	}
}