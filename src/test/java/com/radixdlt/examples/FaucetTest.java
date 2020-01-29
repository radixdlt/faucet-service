/*
 * (C) Copyright 2020 Radix DLT Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the “Software”),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.radixdlt.examples;

import com.radixdlt.client.application.RadixApplicationAPI;
import com.radixdlt.client.application.identity.RadixIdentities;
import com.radixdlt.client.application.identity.RadixIdentity;
import com.radixdlt.client.application.translate.data.DecryptedMessage;
import com.radixdlt.client.application.translate.data.DecryptedMessage.EncryptionState;
import com.radixdlt.client.application.translate.tokens.CreateTokenAction.TokenSupplyType;
import com.radixdlt.client.application.translate.tokens.TokenDefinitionReference;
import com.radixdlt.client.core.Bootstrap;
import com.radixdlt.client.core.RadixUniverse;
import io.reactivex.functions.Predicate;
import io.reactivex.observers.TestObserver;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.radix.utils.UInt256;

public class FaucetTest {
	@Test
	public void test() throws Exception {
		RadixUniverse.create(Bootstrap.LOCALHOST);

		final RadixIdentity faucetIdentity = RadixIdentities.createNew();
		final RadixApplicationAPI faucetApi = RadixApplicationAPI.create(Bootstrap.LOCALHOST, faucetIdentity);
		faucetApi.createToken(
			"Faucet Token",
			"FCT",
			"Faucet Token",
			BigDecimal.valueOf(1000000),
			BigDecimal.ONE,
			TokenSupplyType.MUTABLE
		)
		.toCompletable()
		.blockingAwait();

		final TokenDefinitionReference tokenRef = TokenDefinitionReference.of(faucetApi.getMyAddress(), "FCT");
		final Faucet faucet = new Faucet(faucetApi, tokenRef);
		faucet.run();

		for (int i = 0; i < 10; i++) {
			final RadixIdentity userIdentity = RadixIdentities.createNew();
			final RadixApplicationAPI userApi = RadixApplicationAPI.create(Bootstrap.LOCALHOST, userIdentity);
			final String message = "Hello " + i;
			userApi.sendMessage(message.getBytes(), false, faucetApi.getMyAddress())
				.toCompletable()
				.blockingAwait();

			TimeUnit.SECONDS.sleep(5);

			TestObserver<DecryptedMessage> messageObserver = TestObserver.create();
			userApi.getMessages().subscribe(messageObserver);
			messageObserver.awaitCount(2);
			final Predicate<DecryptedMessage> check = msg -> {
				if (msg.getFrom().equals(userApi.getMyAddress())) {
					return msg.getEncryptionState().equals(EncryptionState.NOT_ENCRYPTED)
						&& new String(msg.getData()).equals(message);
				} else {
					return msg.getEncryptionState().equals(EncryptionState.DECRYPTED)
						&& new String(msg.getData()).equals("Sent you 10 " + tokenRef.getSymbol() + "!");
				}
			};
			messageObserver.assertValueAt(0, check);
			messageObserver.assertValueAt(1, check);
			messageObserver.dispose();

			TestObserver<BigDecimal> balanceObserver = TestObserver.create();
			faucetApi.getMyBalance(tokenRef)
				.firstOrError().subscribe(balanceObserver);
			final BigDecimal expected = BigDecimal.valueOf(1000000).subtract(BigDecimal.TEN.multiply(BigDecimal.valueOf(i + 1)));
			balanceObserver.awaitTerminalEvent();
			balanceObserver.assertValue(bal -> bal.compareTo(expected) == 0);
			balanceObserver.dispose();

			TestObserver<BigDecimal> userBalanceObserver = TestObserver.create();
			userApi.getMyBalance(tokenRef)
				.firstOrError().subscribe(userBalanceObserver);
			userBalanceObserver.awaitTerminalEvent();
			userBalanceObserver.assertValue(bal -> bal.compareTo(BigDecimal.TEN) == 0);
			userBalanceObserver.dispose();
		}
	}
}