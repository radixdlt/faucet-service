package com.radixdlt.examples;

import com.google.common.collect.ImmutableMap;
import com.radixdlt.client.application.RadixApplicationAPI;
import com.radixdlt.client.application.RadixApplicationAPI.Result;
import com.radixdlt.client.application.RadixApplicationAPI.Transaction;
import com.radixdlt.client.application.identity.RadixIdentities;
import com.radixdlt.client.application.identity.RadixIdentity;
import com.radixdlt.client.application.translate.data.DecryptedMessage;
import com.radixdlt.client.application.translate.data.SendMessageAction;
import com.radixdlt.client.application.translate.tokens.TransferTokensAction;
import com.radixdlt.client.application.translate.unique.PutUniqueIdAction;
import com.radixdlt.client.atommodel.accounts.RadixAddress;
import com.radixdlt.client.core.Bootstrap;
import com.radixdlt.client.core.atoms.particles.RRI;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.radix.utils.RadixConstants;

/**
 * A service which sends tokens to whoever sends it a message through
 * a Radix Universe.
 */
public class Faucet {
	private static final String RADIX_BOOTSTRAP_CONFIG_ENV_NAME = "RADIX_BOOTSTRAP_CONFIG";
	private static final String RADIX_IDENTITY_KEY_FILE_ENV_NAME = "RADIX_IDENTITY_KEY_FILE";
	private static final String RADIX_IDENTITY_KEY_FILE_PASSWORD_ENV_NAME = "RADIX_IDENTITY_KEY_FILE_PASSWORD";
	private static final String FAUCET_TOKEN_RRI_ENV_NAME = "FAUCET_TOKEN_RRI";
	private static final String FAUCET_DELAY_ENV_NAME = "FAUCET_DELAY";
	private static final String UNIQUE_MESSAGE_PREFIX = "faucet-msg-";
	private static final String UNIQUE_SEND_TOKENS_PREFIX = "faucet-tx-";
	private static final long DEFAULT_DELAY = 1000 * 60 * 10; //10min

	private final RadixApplicationAPI api;
	private final RRI tokenRRI;
	private final BigDecimal amountToSend;
	private final long delay;

	private Faucet(RadixApplicationAPI api, RRI tokenRRI, BigDecimal amountToSend, long delay) {
		this.tokenRRI = Objects.requireNonNull(tokenRRI);
		this.api = Objects.requireNonNull(api);
		this.amountToSend = Objects.requireNonNull(amountToSend);
		this.delay = delay;
	}

	/**
	 * Send tokens from this account to an address
	 *
	 * @param msg the msg received
	 * @return completable whether transfer was successful or not
	 */
	private void leakFaucet(RateLimiter rateLimiter, DecryptedMessage msg) {
		RRI msgMutexAcquire = RRI.of(api.getAddress(), UNIQUE_MESSAGE_PREFIX + msg.getActionId());
		RRI transferMutexAcquire = RRI.of(api.getAddress(), UNIQUE_SEND_TOKENS_PREFIX + msg.getActionId());

		if (!rateLimiter.check()) {
			Transaction hastyMsg = this.api.createTransaction();
			hastyMsg.stage(SendMessageAction.create(
				api.getAddress(),
				msg.getFrom(),
				("Don't be hasty! Time remaining before new request accepted: " + rateLimiter.getTimeLeftString())
					.getBytes(RadixConstants.STANDARD_CHARSET),
				true
			));
			hastyMsg.stage(PutUniqueIdAction.create(msgMutexAcquire));
			hastyMsg.stage(PutUniqueIdAction.create(transferMutexAcquire));
			hastyMsg.commitAndPush().toObservable().subscribe(System.out::println, Throwable::printStackTrace);
			return;
		}

		Transaction transaction = this.api.createTransaction();
		transaction.stage(TransferTokensAction.create(tokenRRI, api.getAddress(), msg.getFrom(), amountToSend));
		transaction.stage(PutUniqueIdAction.create(transferMutexAcquire));
		Result result = transaction.commitAndPush();
		result.toObservable().subscribe(
			s -> System.out.println("Send tokens for " + msg.getActionId() + ": " + s),
			e -> System.out.println("Could not send tokens: " + e)
		);
		result.toCompletable().subscribe(
			() -> {
				Transaction sentRadsMsg = this.api.createTransaction();
				byte[] msgBytes = ("Sent you " + amountToSend + " " + tokenRRI.getName()).getBytes(RadixConstants.STANDARD_CHARSET);
				sentRadsMsg.stage(SendMessageAction.create(api.getAddress(), msg.getFrom(), msgBytes, true));
				sentRadsMsg.stage(PutUniqueIdAction.create(msgMutexAcquire));
				sentRadsMsg.commitAndPush().toObservable().subscribe(System.out::println, Throwable::printStackTrace);
			},
			e -> {
				Transaction sentRadsMsg = this.api.createTransaction();
				byte[] msgBytes = ("Couldn't send you any (Reason: " + e.getMessage() + ")").getBytes(RadixConstants.STANDARD_CHARSET);
				sentRadsMsg.stage(SendMessageAction.create(api.getAddress(), msg.getFrom(), msgBytes, true));
				sentRadsMsg.stage(PutUniqueIdAction.create(msgMutexAcquire));
				sentRadsMsg.commitAndPush().toObservable().subscribe(System.out::println, Throwable::printStackTrace);
			}
		);
	}

	/**
	 * Start and run the faucet service
	 */
	public void run() {
		api.pull();

		final RadixAddress sourceAddress = api.getAddress();

		System.out.println("Faucet Token: " + tokenRRI);
		System.out.println("Faucet Address: " + sourceAddress);

		// Print out current balance of faucet
		api.observeBalance(tokenRRI)
			.subscribe(
				balance -> System.out.println("Faucet Balance: " + balance),
				Throwable::printStackTrace
			);

		api.observeMessages()
			.groupBy(DecryptedMessage::getFrom)
			.subscribe(observableByAddress -> {
				final RateLimiter rateLimiter = new RateLimiter(delay);

				observableByAddress
					.doOnNext(System.out::println) // Print out all messages
					.filter(message -> !message.getFrom().equals(sourceAddress)) // Don't send ourselves money
					.subscribe(message -> this.leakFaucet(rateLimiter, message), Throwable::printStackTrace);
			});

		try {
			TimeUnit.SECONDS.sleep(5);
		} catch (InterruptedException e) {
		}
	}

	/**
	 * Simple Rate Limiter helper class
	 */
	private static class RateLimiter {
		private final AtomicLong lastTimestamp = new AtomicLong();
		private final long millis;

		private RateLimiter(long millis) {
			this.millis = millis;
		}

		String getTimeLeftString() {
			long timeSince = System.currentTimeMillis() - lastTimestamp.get();
			long secondsTimeLeft = ((this.millis - timeSince) / 1000) % 60;
			long minutesTimeLeft = ((this.millis - timeSince) / 1000) / 60;
			return minutesTimeLeft + " minutes and " + secondsTimeLeft + " seconds";
		}

		boolean check() {
			return lastTimestamp.get() == 0 || (System.currentTimeMillis() - lastTimestamp.get() > millis);
		}

		void reset() {
			lastTimestamp.set(System.currentTimeMillis());
		}
	}

	public static void main(String[] args) throws Exception {
		final String universeOptions = String.join("|", Arrays.stream(Bootstrap.values()).map(Bootstrap::name).collect(Collectors.toList()));
		final Map<String, String> requiredEnvVars = ImmutableMap.of(
			RADIX_BOOTSTRAP_CONFIG_ENV_NAME, " must be set to: <" + universeOptions + ">",
			FAUCET_TOKEN_RRI_ENV_NAME, " must be set to: <rri-of-token>",
			RADIX_IDENTITY_KEY_FILE_ENV_NAME, "must be set to: <path-to-keyfile>",
			RADIX_IDENTITY_KEY_FILE_PASSWORD_ENV_NAME, "must be set to: <password>"
		);

		for (String envVar : requiredEnvVars.keySet()) {
			String val = System.getenv(envVar);
			if (val == null) {
				System.out.println("Env var " + envVar + requiredEnvVars.get(envVar));
				System.exit(-1);
			}
		}

		final String universeString = System.getenv(RADIX_BOOTSTRAP_CONFIG_ENV_NAME);
		final String tokenRRIString = System.getenv(FAUCET_TOKEN_RRI_ENV_NAME);
		final String faucetDelayString = System.getenv(FAUCET_DELAY_ENV_NAME);
		final String keyFile = System.getenv(RADIX_IDENTITY_KEY_FILE_ENV_NAME);
		final String password = System.getenv(RADIX_IDENTITY_KEY_FILE_PASSWORD_ENV_NAME);
		final RadixIdentity faucetIdentity = RadixIdentities.loadOrCreateEncryptedFile(keyFile, password);
		final RadixApplicationAPI api = RadixApplicationAPI.create(Bootstrap.valueOf(universeString), faucetIdentity);
		final RRI tokenRRI = RRI.fromString(tokenRRIString);
		final long delay = faucetDelayString != null ? Long.parseLong(faucetDelayString) : DEFAULT_DELAY;

		Faucet faucet = new Faucet(api, tokenRRI, BigDecimal.valueOf(10.0), delay);
		faucet.run();
	}
}
