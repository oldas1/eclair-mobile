package fr.acinq.eclair.wallet;

import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.spongycastle.util.encoders.Hex;

import java.io.ByteArrayOutputStream;
import java.util.Date;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import fr.acinq.bitcoin.Protocol;
import fr.acinq.bitcoin.Satoshi;
import fr.acinq.bitcoin.Transaction;
import fr.acinq.bitcoin.package$;
import fr.acinq.eclair.blockchain.electrum.ElectrumClient;
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet;
import fr.acinq.eclair.wallet.events.BitcoinPaymentEvent;
import fr.acinq.eclair.wallet.events.ElectrumConnectionEvent;
import fr.acinq.eclair.wallet.events.WalletBalanceUpdateEvent;
import fr.acinq.eclair.wallet.models.Payment;
import fr.acinq.eclair.wallet.models.PaymentDirection;
import fr.acinq.eclair.wallet.models.PaymentType;

/**
 * This actor handles the various messages received from Electrum Wallet.
 */
public class PaymentSupervisor extends UntypedActor {
  public final static String TAG = "PaymentSupervisor";
  private App app;
  private ActorRef wallet;

  public PaymentSupervisor(App app, ActorRef wallet) {
    this.app = app;
    this.wallet = wallet;
    context().system().eventStream().subscribe(self(), ElectrumClient.ElectrumEvent.class);
    context().system().eventStream().subscribe(self(), ElectrumWallet.WalletEvent.class);
  }

  /**
   * Handles messages from the wallet: new txs, balance update, tx confidences update.
   *
   * @param message message sent by the wallet
   * @throws Exception
   */
  public void onReceive(final Object message) throws Exception {
    if (message instanceof ElectrumWallet.TransactionReceived) {
      Log.d(TAG, "Received TransactionReceived message: " + message);
      ElectrumWallet.TransactionReceived walletTransactionReceive = (ElectrumWallet.TransactionReceived) message;
      final Transaction tx = walletTransactionReceive.tx();
      final PaymentDirection direction = (walletTransactionReceive.received().$greater$eq(walletTransactionReceive.sent()))
        ? PaymentDirection.RECEIVED
        : PaymentDirection.SENT;
      final Satoshi amount = (walletTransactionReceive.received().$greater$eq(walletTransactionReceive.sent()))
        ? walletTransactionReceive.received().$minus(walletTransactionReceive.sent())
        : walletTransactionReceive.sent().$minus(walletTransactionReceive.received());
      final Payment paymentInDB = app.getDBHelper().getPayment(tx.txid().toString(), PaymentType.BTC_ONCHAIN, direction);
      final Satoshi fee = walletTransactionReceive.feeOpt().isDefined() ? walletTransactionReceive.feeOpt().get() : new Satoshi(0);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      Transaction.write(tx, bos, Protocol.PROTOCOL_VERSION());
      Log.d(TAG, "WalletTransactionReceive tx = [ " + walletTransactionReceive.tx().txid()
        + ", amt " + amount + ", fee " + fee + ", dir " + direction
        + ", already known " + (paymentInDB != null) + " ]");

      // insert or update received payment in DB
      final Payment paymentReceived = paymentInDB == null ? new Payment() : paymentInDB;
      paymentReceived.setType(PaymentType.BTC_ONCHAIN);
      paymentReceived.setDirection(direction);
      paymentReceived.setReference(walletTransactionReceive.tx().txid().toString());
      if (direction == PaymentDirection.SENT) { // fee makes sense only if the tx is sent by us
        paymentReceived.setFeesPaidMsat(package$.MODULE$.satoshi2millisatoshi(fee).amount());
      }
      paymentReceived.setTxPayload(Hex.toHexString(bos.toByteArray()));
      paymentReceived.setAmountPaidMsat(package$.MODULE$.satoshi2millisatoshi(amount).amount());
      paymentReceived.setConfidenceBlocks((int) walletTransactionReceive.depth());
      paymentReceived.setConfidenceType(0);

      if (paymentInDB == null) {
        // timestamp is updated only if the transaction is not already known
        paymentReceived.setUpdated(new Date());
      }
      app.getDBHelper().insertOrUpdatePayment(paymentReceived);

      // dispatch news and ask for on-chain balance update
      EventBus.getDefault().post(new BitcoinPaymentEvent(paymentReceived));
    } else if (message instanceof ElectrumWallet.TransactionConfidenceChanged) {
      Log.d(TAG, "Received TransactionConfidenceChanged message: " + message);
      final ElectrumWallet.TransactionConfidenceChanged walletTransactionConfidenceChanged = (ElectrumWallet.TransactionConfidenceChanged) message;
      final int depth = (int) walletTransactionConfidenceChanged.depth();
      if (depth < 10) { // ignore tx with confidence > 10 for perfs reasons
        final Payment p = app.getDBHelper().getPayment(walletTransactionConfidenceChanged.txid().toString(), PaymentType.BTC_ONCHAIN);
        if (p != null) {
          p.setConfidenceBlocks(depth);
          app.getDBHelper().updatePayment(p);
          EventBus.getDefault().post(new BitcoinPaymentEvent(null));
        }
      }
    } else if (message instanceof ElectrumWallet.WalletReady) {
      Log.d(TAG, "Received WalletReady message: {}" + message);
      ElectrumWallet.WalletReady ready = (ElectrumWallet.WalletReady) message;
      app.onChainBalance.set(ready.confirmedBalance().$plus(ready.unconfirmedBalance()));
      EventBus.getDefault().post(new WalletBalanceUpdateEvent());
    } else if (message instanceof ElectrumWallet.NewWalletReceiveAddress) {
      Log.d(TAG, "Received NewWalletReceiveAddress message: {}" + message);
      ElectrumWallet.NewWalletReceiveAddress address = (ElectrumWallet.NewWalletReceiveAddress) message;
      EventBus.getDefault().postSticky(address);
    } else if (message instanceof ElectrumClient.ElectrumDisconnected$) {
      Log.d(TAG, "Received DISCONNECTED");
      EventBus.getDefault().post(new ElectrumConnectionEvent(false));
    } else if (message instanceof ElectrumClient.ElectrumConnected$) {
      Log.d(TAG, "Received CONNECTED");
      EventBus.getDefault().postSticky(new ElectrumConnectionEvent(true));
    } else unhandled(message);
  }
}
