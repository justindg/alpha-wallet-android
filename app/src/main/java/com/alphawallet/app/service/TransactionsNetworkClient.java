package com.alphawallet.app.service;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.alphawallet.app.entity.ContractType;
import com.alphawallet.app.entity.CovalentTransaction;
import com.alphawallet.app.entity.EtherscanEvent;
import com.alphawallet.app.entity.EtherscanTransaction;
import com.alphawallet.app.entity.NetworkInfo;
import com.alphawallet.app.entity.Transaction;
import com.alphawallet.app.entity.TransactionMeta;
import com.alphawallet.app.entity.Wallet;
import com.alphawallet.app.entity.opensea.Asset;
import com.alphawallet.app.entity.tokens.ERC721Token;
import com.alphawallet.app.entity.tokens.Token;
import com.alphawallet.app.entity.tokens.TokenFactory;
import com.alphawallet.app.entity.tokens.TokenInfo;
import com.alphawallet.app.entity.tokenscript.EventUtils;
import com.alphawallet.app.repository.TokenRepository;
import com.alphawallet.app.repository.TransactionsRealmCache;
import com.alphawallet.app.repository.entity.RealmAuxData;
import com.alphawallet.app.repository.entity.RealmToken;
import com.alphawallet.app.repository.entity.RealmTransaction;
import com.alphawallet.app.repository.entity.RealmTransfer;
import com.alphawallet.app.util.Utils;
import com.alphawallet.token.entity.ContractAddress;
import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthCall;

import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.Sort;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import static com.alphawallet.app.entity.TransactionDecoder.FUNCTION_LENGTH;
import static com.alphawallet.app.repository.EthereumNetworkBase.COVALENT;
import static com.alphawallet.app.repository.TokenRepository.getWeb3jService;
import static com.alphawallet.app.repository.TokensRealmSource.databaseKey;
import static org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction;

public class TransactionsNetworkClient implements TransactionsNetworkClientType
{
    private final int PAGESIZE = 800;
    private final int SYNC_PAGECOUNT = 2; //how many pages to read when we first sync the account - means we store the first 1600 transactions only
    //Note: if user wants to view transactions older than this, we fetch from etherscan on demand.
    //Generally this would only happen when watching extremely active accounts for curiosity
    private final String BLOCK_ENTRY = "-erc20blockCheck-";
    private final String ERC20_QUERY = "tokentx";
    private final String ERC721_QUERY = "tokennfttx";
    private final int AUX_DATABASE_ID = 5; //increment this to do a one off refresh the AUX database, in case of changed design etc
    private final String DB_RESET = BLOCK_ENTRY + AUX_DATABASE_ID;
    private final String ETHERSCAN_API_KEY = "&apikey=6U31FTHW3YYHKW6CYHKKGDPHI9HEJ9PU5F";

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final RealmManager realmManager;

    public TransactionsNetworkClient(
            OkHttpClient httpClient,
            Gson gson,
            RealmManager realmManager) {
        this.httpClient = httpClient;
        this.gson = gson;
        this.realmManager = realmManager;
    }

    /**
     *
     * Transaction sync strategy:
     *
     * Unsynced or out of sync:
     *
     * Read first 2400 transactions (3 pages) from top down.
     *
     * Delete any transactions in database older than 2400th stored tx
     *
     * Synced:
     *
     * Read upwards from sync point
     *
     *
     * On user scrolling down transaction list: if user is nearing bottom transaction limit begin fetching next 800
     *
     */

    /**
     * Scans the transactions for an address and stores new transactions in the database
     *
     * If first sync, sync from top and read 2 pages.
     * If not first sync, sync upward from existing entry.
     * If reading more than one page, blank database and start from first sync.
     *
     * @param walletAddress
     * @param networkInfo
     * @param lastBlock
     * @return
     */
    @Override
    public Single<Transaction[]> storeNewTransactions(String walletAddress, NetworkInfo networkInfo, String tokenAddress, final long lastBlock)
    {
        return Single.fromCallable(() -> {
            long lastBlockNumber = lastBlock + 1;
            Map<String, Transaction> updates = new HashMap<>();
            EtherscanTransaction lastTransaction = null;
            try (Realm instance = realmManager.getRealmInstance(walletAddress))
            {
                if (lastBlockNumber == 1) //first read of account. Read first 2 pages
                {
                    lastTransaction = syncDownwards(updates, instance, walletAddress, networkInfo, tokenAddress, 9999999999L);
                }
                else // try to sydenc upwards from the last read
                {
                    lastTransaction = syncUpwards(updates, instance, walletAddress, networkInfo, tokenAddress, lastBlockNumber);
                }
            }
            catch (JSONException e)
            {
                //silent fail
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                //ensure transaction check time is always written
                String lastBlockRead = (lastTransaction != null) ? lastTransaction.blockNumber : String.valueOf(lastBlock);
                storeLatestBlockRead(walletAddress, networkInfo.chainId, tokenAddress, lastBlockRead);
            }

            return updates.values().toArray(new Transaction[0]);
        }).subscribeOn(Schedulers.io());
    }

    /**
     * read PAGESIZE*2 transactions down from startingBlockNumber
     *
     * Note that this call is the only place that the 'earliest transaction' block can be written from.
     */
    private EtherscanTransaction syncDownwards(Map<String, Transaction> updates, Realm instance, String walletAddress, NetworkInfo networkInfo, String tokenAddress, long startingBlockNumber) throws Exception
    {
        int page = 1;
        List<Transaction> txList = new ArrayList<>();
        EtherscanTransaction firstTransaction = null;
        EtherscanTransaction lastTransaction = null;
        boolean continueReading = true;

        while (continueReading) // only SYNC_PAGECOUNT pages at a time for each check, to avoid congestion
        {
            EtherscanTransaction[] myTxs = readTransactions(networkInfo, walletAddress, tokenAddress, String.valueOf(startingBlockNumber), false, page++, PAGESIZE);
            if (myTxs == null) break;
            getRelatedTransactionList(txList, myTxs, walletAddress, networkInfo.chainId);
            if (myTxs.length > 0 && firstTransaction == null)
            {
                firstTransaction = myTxs[0];
            }
            if (myTxs.length > 0)
            {
                lastTransaction = myTxs[myTxs.length - 1];
            }

            writeTransactions(instance, txList); //record transactions here
            writeUpdates(updates, txList);

            if (page > SYNC_PAGECOUNT) continueReading = false;

            if (myTxs.length < PAGESIZE)
            {
                continueReading = false;
                //store earliest transaction
                if (lastTransaction != null) storeEarliestBlockRead(instance, networkInfo.chainId, tokenAddress, Long.parseLong(lastTransaction.blockNumber));
            }
        }

        return firstTransaction;
    }

    private void writeUpdates(Map<String, Transaction> updates, List<Transaction> txList)
    {
        if (updates != null)
        {
            for (Transaction tx : txList)
            {
                updates.put(tx.hash, tx);
            }
        }
    }

    private EtherscanTransaction syncUpwards(Map<String, Transaction> updates, Realm instance, String walletAddress, NetworkInfo networkInfo, String tokenAddress, long lastBlockNumber) throws Exception
    {
        int page = 1;
        List<Transaction> txList = new ArrayList<>();
        EtherscanTransaction lastTransaction;

        //only sync upwards by 1 page. If not sufficient then reset; delete DB and start again
        EtherscanTransaction[] myTxs = readTransactions(networkInfo, walletAddress, tokenAddress, String.valueOf(lastBlockNumber), true, page, PAGESIZE);
        if (myTxs == null) return null;
        if (myTxs.length == PAGESIZE)
        {
            //too big, erase transaction list and start from top
            deleteAllChainTransactions(instance, networkInfo.chainId, walletAddress);
            lastTransaction = syncDownwards(updates, instance, walletAddress, networkInfo, tokenAddress, 999999999L); //re-sync downwards from top
            lastTransaction.nonce = -1; //signal to refresh list - this doesn't get written to DB
            return lastTransaction;
        }
        else
        {
            getRelatedTransactionList(txList, myTxs, walletAddress, networkInfo.chainId);
            writeTransactions(instance, txList); //record transactions here
            writeUpdates(updates, txList);
            lastTransaction = myTxs[myTxs.length-1];
            return lastTransaction;
        }
    }

    private void getRelatedTransactionList(List<Transaction> txList, EtherscanTransaction[] myTxs, String walletAddress, int chainId)
    {
        txList.clear();
        for (EtherscanTransaction etx : myTxs)
        {
            Transaction tx = etx.createTransaction(walletAddress, chainId);
            if (tx != null)
            {
                txList.add(tx);
            }
        }
    }

    private EtherscanTransaction[] getEtherscanTransactions(String response) throws JSONException
    {
        JSONObject stateData = new JSONObject(response);
        JSONArray orders = stateData.getJSONArray("result");
        return gson.fromJson(orders.toString(), EtherscanTransaction[].class);
    }

    private EtherscanTransaction[] getEtherscanTransactionsFromCovalent(String response, String walletAddress, NetworkInfo info) throws JSONException
    {
        JSONObject stateData = new JSONObject(response);
        JSONObject data = stateData.getJSONObject("data");
        JSONArray orders = data.getJSONArray("items");
        CovalentTransaction[] ctxs = gson.fromJson(orders.toString(), CovalentTransaction[].class);
        //reformat list to remove any transactions already seen
        List<CovalentTransaction> cvList = new ArrayList<>();
        try (Realm instance = realmManager.getRealmInstance(new Wallet(walletAddress)))
        {
            for (CovalentTransaction ctx : ctxs)
            {
                RealmTransaction realmTx = instance.where(RealmTransaction.class)
                        .equalTo("hash", ctx.tx_hash)
                        .findFirst();

                if (realmTx == null)
                {
                    cvList.add(ctx);
                }
            }
        }

        return CovalentTransaction.toEtherscanTransactions(cvList, info);
    }

    private EtherscanEvent[] getEtherscanEvents(String response) throws JSONException
    {
        JSONObject stateData = new JSONObject(response);
        JSONArray orders = stateData.getJSONArray("result");
        return gson.fromJson(orders.toString(), EtherscanEvent[].class);
    }

    private boolean writeTransactions(Realm instance, List<Transaction> txList) throws Exception
    {
        if (txList.size() == 0) return false;
        boolean startedReWriting = false;

        instance.beginTransaction();
        for (Transaction tx : txList)
        {
            RealmTransaction realmTx = instance.where(RealmTransaction.class)
                    .equalTo("hash", tx.hash)
                    .findFirst();

            if (realmTx == null)
            {
                realmTx = instance.createObject(RealmTransaction.class, tx.hash);
            }
            else
            {
                startedReWriting = true;
            }

            TransactionsRealmCache.fill(instance, realmTx, tx);
            instance.insertOrUpdate(realmTx);
        }
        instance.commitTransaction();

        return startedReWriting;
    }

    private EtherscanTransaction[] readTransactions(NetworkInfo networkInfo, String walletAddress, String tokenAddress, String firstBlock, boolean ascending, int page, int pageSize) throws JSONException
    {
        if (networkInfo.etherscanTxUrl.contains(COVALENT)) { return readCovalentTransactions(walletAddress, tokenAddress, networkInfo, ascending, page, pageSize); }
        okhttp3.Response response;
        String result = null;
        String fullUrl;

        String sort = "asc";
        if (!ascending) sort = "desc";

        if (networkInfo != null && !TextUtils.isEmpty(networkInfo.etherscanTxUrl))
        {
            StringBuilder sb = new StringBuilder();
            sb.append(networkInfo.etherscanTxUrl);
            if (!networkInfo.etherscanTxUrl.endsWith("/"))
            {
                sb.append("/");
            }

            sb.append("api?module=account&action=txlist&address=");
            sb.append(tokenAddress);
            if (ascending)
            {
                sb.append("&startblock=");
                sb.append(firstBlock);
                sb.append("&endblock=999999999&sort=");
            }
            else
            {
                sb.append("&startblock=0");
                sb.append("&endblock=");
                sb.append(firstBlock);
                sb.append("&sort=");
            }

            sb.append(sort);
            if (page > 0)
            {
                sb.append("&page=");
                sb.append(page);
                sb.append("&offset=");
                sb.append(pageSize);
            }

            if (networkInfo.etherscanTxUrl.contains("etherscan"))
            {
                sb.append(ETHERSCAN_API_KEY);
            }

            fullUrl = sb.toString();

            try
            {
                Request request = new Request.Builder()
                        .url(fullUrl)
                        .get()
                        .build();

                response = httpClient.newCall(request).execute();

                result = response.body().string();
                if (result != null && result.length() < 80 && result.contains("No transactions found"))
                {
                    result = "0";
                }
            }
            catch (InterruptedIOException e)
            {
                //If user switches account or network during a fetch
                //this exception is going to be thrown because we're terminating the API call
                //Don't display error
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        return getEtherscanTransactions(result);
    }

    /**
     * This is the function called when a user scrolls to the bottom of a transaction list.
     * First try to provide more transactions from the stored database. If there aren't any more then populate another page (800) from etherscan
     *
     * @param walletAddress
     * @param network
     * @param lastTxTime
     * @return
     */
    @Override
    public Single<TransactionMeta[]> fetchMoreTransactions(String walletAddress, NetworkInfo network, long lastTxTime)
    {
        return Single.fromCallable(() -> {
            List<TransactionMeta> txList = fetchOlderThan(walletAddress, lastTxTime, network.chainId);
            if (txList.size() < 800)
            {
                //fetch another page and return unless we already have the oldest Tx
                long oldestTxTime = txList.size() > 0 ? txList.get(txList.size() - 1).getTimeStampSeconds() : lastTxTime;
                try (Realm instance = realmManager.getRealmInstance(new Wallet(walletAddress)))
                {
                    long oldestBlockRead = getOldestBlockRead(instance, network.chainId, oldestTxTime);
                    long oldestPossibleBlock = getFirstTransactionBlock(instance, network.chainId, walletAddress);
                    System.out.println("DIAGNOSE: " + oldestBlockRead + " : " + oldestPossibleBlock);
                    if (oldestBlockRead > 0 && oldestBlockRead != oldestPossibleBlock)
                    {
                        syncDownwards(null, instance, walletAddress, network, walletAddress, oldestBlockRead);
                    }

                    //now re-read last blocks from DB
                    txList = fetchOlderThan(walletAddress, lastTxTime, network.chainId);

                }
                catch (Exception e)
                {
                    //
                }
            }

            return txList.toArray(new TransactionMeta[0]);
        });
    }

    @Override
    public Single<Integer> readTransfers(String walletAddress, NetworkInfo networkInfo, TokensService svs, boolean nftCheck)
    {
        if (nftCheck)
        {
            return readNFTTransactions(walletAddress, networkInfo, svs);
        }
        else
        {
            return readERC20Transactions(walletAddress, networkInfo, svs);
        }
    }

    //See if we require a refresh of transaction checks
    @Override
    public void checkTransactionsForEmptyFunctions(String currentAddress)
    {
        try (Realm instance = realmManager.getRealmInstance(new Wallet(currentAddress)))
        {
            instance.executeTransactionAsync(r -> {
                RealmResults<RealmAuxData> checkMarkers = r.where(RealmAuxData.class)
                        .like("instanceKey", BLOCK_ENTRY + "*")
                        .findAll();

                boolean delete = false;

                for (RealmAuxData aux : checkMarkers)
                {
                    if (TextUtils.isEmpty(aux.getResult()) || !aux.getResult().equals(DB_RESET))
                    {
                        String chainIdStr = aux.getInstanceKey().substring(BLOCK_ENTRY.length());
                        int chainId = Integer.parseInt(chainIdStr);
                        writeNFTokenBlockRead(r, chainId, 0); //check from start
                        writeTokenBlockRead(r, chainId, 0); //check from start
                        aux.setResult(DB_RESET);
                        delete = true;
                    }
                }

                if (delete)
                {
                    RealmResults<RealmAuxData> realmEvents = r.where(RealmAuxData.class)
                            .findAll();
                    realmEvents.deleteAllFromRealm();
                    RealmResults<RealmTransfer> realmTransfers = r.where(RealmTransfer.class)
                            .findAll();
                    realmTransfers.deleteAllFromRealm();
                }
            });
        }
        catch (Exception e)
        {
            //
        }
    }

    /**
     * Fetch the ERC20 transactions relevant to this wallet - ie deposits to and transfers from
     * @param walletAddress
     * @param networkInfo
     * @param svs
     * @return
     */
    public Single<Integer> readERC20Transactions(String walletAddress, NetworkInfo networkInfo, TokensService svs)
    {
        return Single.fromCallable(() -> {
            //get latest block read
            int eventCount = 0;
            try (Realm instance = realmManager.getRealmInstance(new Wallet(walletAddress)))
            {
                //get last tokencheck
                long lastBlockChecked = getTokenBlockRead(instance, networkInfo.chainId);
                //fetch erc20 tx from Etherscan
                String fetchTransactions = readNextTxBatch(walletAddress, networkInfo, lastBlockChecked, ERC20_QUERY);

                if (fetchTransactions != null && fetchTransactions.length() > 100)
                {
                    //convert to gson
                    EtherscanEvent[] events = getEtherscanEvents(fetchTransactions);
                    //we know all these events are relevant to the wallet, and they are all ERC20 events
                    writeEvents(instance, events, walletAddress, networkInfo, false);

                    //Now update tokens if we don't already know this token
                    writeERC20Tokens(instance, walletAddress, networkInfo, events, svs);

                    lastBlockChecked = Long.parseLong(events[events.length - 1].blockNumber);

                    //and update the top block read
                    writeTokenBlockRead(instance, networkInfo.chainId, lastBlockChecked);
                    eventCount = events.length;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            return eventCount;
        }).observeOn(Schedulers.io());
    }

    public Single<Integer> readNFTTransactions(String walletAddress, @NonNull NetworkInfo networkInfo, TokensService svs)
    {
        return Single.fromCallable(() -> {
            //get latest block read
            int eventCount = 0;
            try (Realm instance = realmManager.getRealmInstance(new Wallet(walletAddress)))
            {
                //get last tokencheck
                long lastBlockChecked = getNFTokenBlockRead(instance, networkInfo.chainId);
                String fetchTransactions = readNextTxBatch(walletAddress, networkInfo, lastBlockChecked, ERC721_QUERY);

                if (fetchTransactions != null && fetchTransactions.length() > 100)
                {
                    //convert to gson
                    EtherscanEvent[] events = getEtherscanEvents(fetchTransactions);
                    writeEvents(instance, events, walletAddress, networkInfo, true);

                    //Now update tokens if we don't already know this token
                    writeERC721Tokens(walletAddress, networkInfo, events, svs);

                    lastBlockChecked = Long.parseLong(events[events.length - 1].blockNumber);

                    //and update the top block read
                    writeNFTokenBlockRead(instance, networkInfo.chainId, lastBlockChecked);
                    eventCount = events.length;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            return eventCount;
        }).observeOn(Schedulers.io());
    }

    private void writeERC20Tokens(Realm instance, String walletAddress, NetworkInfo networkInfo, EtherscanEvent[] events, TokensService svs)
    {
        for (EtherscanEvent ev : events)
        {
            //have this token?
            RealmToken realmItem = instance.where(RealmToken.class)
                    .equalTo("address", databaseKey(networkInfo.chainId, ev.contractAddress.toLowerCase()))
                    .equalTo("chainId", networkInfo.chainId)
                    .findFirst();

            int tokenDecimal = (!TextUtils.isEmpty(ev.tokenDecimal) && Character.isDigit(ev.tokenDecimal.charAt(0))) ? Integer.parseInt(ev.tokenDecimal) : -1;

            if (realmItem == null || realmItem.getInterfaceSpec() != ContractType.ERC20.ordinal()
                    || (tokenDecimal > 0 && tokenDecimal != realmItem.getDecimals())
                    || !ev.tokenName.equals(realmItem.getName()) //trust etherscan's name
            )
            {
                // write token to DB - note this also fetches the balance
                if (tokenDecimal >= 0)
                {
                    TokenInfo info = new TokenInfo(ev.contractAddress, ev.tokenName, ev.tokenSymbol, tokenDecimal, true, networkInfo.chainId);
                    Token newToken = new Token(info, BigDecimal.ZERO, 0, networkInfo.getShortName(),
                            tokenDecimal > 0 ? ContractType.ERC20 : ContractType.MAYBE_ERC20);
                    newToken.setTokenWallet(walletAddress);
                    svs.storeToken(newToken);
                }
                else
                {
                    //unknown token
                    svs.addUnknownTokenToCheck(new ContractAddress(networkInfo.chainId, ev.contractAddress));
                }
            }
            else
            {
                //update token block read so we don't check events on this contract
                storeLatestBlockRead(walletAddress, networkInfo.chainId, ev.contractAddress, ev.blockNumber);
            }
        }
    }

    private void writeERC721Tokens(String walletAddress, NetworkInfo networkInfo, EtherscanEvent[] events, TokensService svs)
    {
        Map<String, List<EtherscanEvent>> eventMap = getEventMap(events);

        for (String contract : eventMap.keySet())
        {
            Token token = svs.getToken(networkInfo.chainId, contract);

            if (token == null ||
                    ( token.getInterfaceSpec() != ContractType.ERC721 &&
                            token.getInterfaceSpec() != ContractType.ERC721_LEGACY &&
                            token.getInterfaceSpec() != ContractType.ERC721_TICKET &&
                            token.getInterfaceSpec() != ContractType.ERC721_UNDETERMINED ))
            {
                token = createNewERC721Token(eventMap.get(contract).get(0), networkInfo, walletAddress, false);
            }

            for (EtherscanEvent ev : eventMap.get(contract))
            {
                BigInteger tokenId = getTokenId(ev.tokenID);

                if (tokenId.compareTo(new BigInteger("-1")) == 0) continue;

                if (ev.to.equalsIgnoreCase(walletAddress))
                {
                    //do we already have this token from opensea? Don't overwrite opensea data (opensea can overwrite data from here).
                    if (token.getAssetForToken(ev.tokenID) != null) { continue; }
                    //added a token
                    //need to fetch metadata
                    Asset asset = parseTokenMetadata(token, tokenId);
                    if (asset != null)
                    {
                        if (token.getInterfaceSpec() != ContractType.ERC721) { token = createNewERC721Token(ev, networkInfo, walletAddress, true); }
                        token.addAssetToTokenBalanceAssets(asset);
                    }
                    else
                    {
                        //no asset, create a temporary blank one until opensea fills it in
                        token.addAssetToTokenBalanceAssets(Asset.blankFromToken(token, tokenId.toString()));
                    }
                }
                else
                {
                    //removed a token
                    token.removeBalance(ev.tokenID);
                }
            }

            //Send to storage as soon as each token is done
            token.lastTxTime = System.currentTimeMillis();
            svs.storeToken(token);
        }
    }

    private String readNextTxBatch(String walletAddress, NetworkInfo networkInfo, long lastBlockChecked, String queryType)
    {
        if (networkInfo.etherscanTxUrl.contains(COVALENT)) { return readCovalentTransfers(walletAddress, networkInfo, lastBlockChecked, queryType); }
        okhttp3.Response response;
        String result = null;
        final String START_BLOCK = "[START_BLOCK]";
        final String WALLET_ADDR = "[WALLET_ADDR]";
        final String ETHERSCAN = "[ETHERSCAN]";
        final String QUERY_TYPE = "[QUERY_TYPE]";
        final String APIKEY_TOKEN = "[APIKEY]";
        String fullUrl = ETHERSCAN + "api?module=account&action=" + QUERY_TYPE + "&startBlock=" + START_BLOCK + "&address=" + WALLET_ADDR + "&page=1&offset=100&sort=asc" + APIKEY_TOKEN;
        fullUrl = fullUrl.replace(QUERY_TYPE, queryType).replace(ETHERSCAN, networkInfo.etherscanTxUrl).replace(START_BLOCK, String.valueOf(lastBlockChecked + 1)).replace(WALLET_ADDR, walletAddress);
        if (networkInfo.etherscanTxUrl.contains("etherscan"))
        {
            fullUrl = fullUrl.replace(APIKEY_TOKEN, ETHERSCAN_API_KEY);
        }
        else
        {
            fullUrl = fullUrl.replace(APIKEY_TOKEN, "");
        }

        try
        {
            Request request = new Request.Builder()
                    .url(fullUrl)
                    .get()
                    .build();

            response = httpClient.newCall(request).execute();

            result = response.body().string();
            if (result != null && result.length() < 80 && result.contains("No transactions found"))
            {
                result = "0";
            }
        }
        catch (InterruptedIOException e)
        {
            //If user switches account or network during a fetch
            //this exception is going to be thrown because we're terminating the API call
            //Don't display error
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return result;
    }

    private String readCovalentTransfers(String walletAddress, NetworkInfo networkInfo, long lastBlockChecked, String queryType)
    {
        return ""; //Currently, covalent doesn't support fetching transfer events
    }

    private EtherscanTransaction[] readCovalentTransactions(String walletAddress, String accountAddress, NetworkInfo networkInfo, boolean ascending, int page, int pageSize) throws JSONException
    {
        String covalent = "" + networkInfo.chainId + "/address/" + accountAddress.toLowerCase() + "/transactions_v2/?";
        String args = "block-signed-at-asc=" + (ascending ? "true" : "false") + "&page-number=" + (page - 1) + "&page-size=" + pageSize;
        String fullUrl = networkInfo.etherscanTxUrl.replace(COVALENT, covalent);
        okhttp3.Response response;
        String result = null;

        try
        {
            Request request = new Request.Builder()
                    .url(fullUrl + args)
                    .get()
                    .build();

            response = httpClient.newCall(request).execute();

            result = response.body().string();
            if (result != null && result.length() < 80 && result.contains("No transactions found"))
            {
                result = "0";
            }
        }
        catch (InterruptedIOException e)
        {
            //If user switches account or network during a fetch
            //this exception is going to be thrown because we're terminating the API call
            //Don't display error
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return getEtherscanTransactionsFromCovalent(result, walletAddress, networkInfo);
    }

    private long getTokenBlockRead(Realm instance, int chainId)
    {
        RealmAuxData rd = instance.where(RealmAuxData.class)
                .equalTo("instanceKey", BLOCK_ENTRY + chainId)
                .findFirst();

        if (rd == null)
        {
            return 1L;
        }
        else
        {
            return rd.getResultTime();
        }
    }

    private long getNFTokenBlockRead(Realm instance, int chainId)
    {
        RealmAuxData rd = instance.where(RealmAuxData.class)
                .equalTo("instanceKey", BLOCK_ENTRY + chainId)
                .findFirst();

        if (rd == null)
        {
            return 1L;
        }
        else
        {
            return rd.getResultReceivedTime();
        }
    }

    private void writeTokenBlockRead(Realm instance, int chainId, long lastBlockChecked)
    {
        instance.executeTransactionAsync(r -> {
            RealmAuxData rd = r.where(RealmAuxData.class)
                    .equalTo("instanceKey", BLOCK_ENTRY + chainId)
                    .findFirst();
            if (rd == null)
            {
                rd = r.createObject(RealmAuxData.class, BLOCK_ENTRY + chainId);
                rd.setResult(DB_RESET);
            }
            rd.setResultTime(lastBlockChecked);
        });
    }

    private void writeNFTokenBlockRead(Realm instance, int chainId, long lastBlockChecked)
    {
        instance.executeTransactionAsync(r -> {
            RealmAuxData rd = r.where(RealmAuxData.class)
                    .equalTo("instanceKey", BLOCK_ENTRY + chainId)
                    .findFirst();
            if (rd == null)
            {
                rd = r.createObject(RealmAuxData.class, BLOCK_ENTRY + chainId);
                rd.setResult(DB_RESET);
            }
            rd.setResultReceivedTime(lastBlockChecked);
        });
    }

    private long getOldestBlockRead(Realm instance, int chainId, long lastTxTime)
    {
        long txBlockRead = 0;
        try
        {
            RealmResults<RealmTransaction> txs = instance.where(RealmTransaction.class)
                    .equalTo("chainId", chainId)
                    .sort("timeStamp", Sort.ASCENDING)
                    .limit(1)
                    .findAll();

            if (txs != null && txs.size() > 0)
            {
                String blockNumber = txs.first().getBlockNumber();
                txBlockRead = Long.parseLong(blockNumber);
            }
        }
        catch (Exception e)
        {
            //
        }

        return txBlockRead;
    }

    private long getFirstTransactionBlock(Realm instance, int chainId, String walletAddress)
    {
        long txBlockRead = 0;
        try
        {
            RealmToken realmToken = instance.where(RealmToken.class)
                    .equalTo("address", databaseKey(chainId, walletAddress))
                    .equalTo("chainId", chainId)
                    .findFirst();

            if (realmToken != null)
            {
                txBlockRead = realmToken.getEarliestTransactionBlock();
            }
        }
        catch (Exception e)
        {
            //
        }

        return txBlockRead;
    }

    private List<TransactionMeta> fetchOlderThan(String walletAddress, long fetchTime, int chainId)
    {
        List<TransactionMeta> metas = new ArrayList<>();
        try (Realm instance = realmManager.getRealmInstance(walletAddress.toLowerCase()))
        {
            RealmResults<RealmTransaction> txs = instance.where(RealmTransaction.class)
                    .sort("timeStamp", Sort.DESCENDING)
                    .lessThan("timeStamp", fetchTime)
                    .equalTo("chainId", chainId)
                    .limit(PAGESIZE)
                    .findAll();

            for (RealmTransaction item : txs)
            {
                TransactionMeta tm = new TransactionMeta(item.getHash(), item.getTimeStamp(), item.getTo(), item.getChainId(), item.getBlockNumber());
                metas.add(tm);
            }
        }
        catch (Exception e)
        {
            //
        }

        return metas;
    }

    private void storeLatestBlockRead(String walletAddress, int chainId, String tokenAddress, String lastBlockRead)
    {
        try (Realm instance = realmManager.getRealmInstance(walletAddress))
        {
            instance.executeTransactionAsync(r -> {
                RealmToken realmToken = r.where(RealmToken.class)
                        .equalTo("address", databaseKey(chainId, tokenAddress))
                        .equalTo("chainId", chainId)
                        .findFirst();

                if (realmToken != null)
                {
                    realmToken.setLastBlock(Long.parseLong(lastBlockRead));
                    realmToken.setLastTxTime(System.currentTimeMillis());
                }
            });
        }
        catch (Exception e)
        {
            //
        }
    }

    private void storeEarliestBlockRead(Realm instance, int chainId, String walletAddress, long earliestBlock)
    {
        try
        {
            instance.executeTransactionAsync(r -> {
                RealmToken realmToken = r.where(RealmToken.class)
                        .equalTo("address", databaseKey(chainId, walletAddress))
                        .equalTo("chainId", chainId)
                        .findFirst();

                if (realmToken != null)
                {
                    realmToken.setEarliestTransactionBlock(earliestBlock);
                }
            });
        }
        catch (Exception e)
        {
            //
        }
    }

    public void deleteAllChainTransactions(Realm instance, int chainId, String walletAddress)
    {
        try
        {
            instance.executeTransaction(r -> {
                RealmResults<RealmTransaction> txs = r.where(RealmTransaction.class)
                        .equalTo("chainId", chainId)
                        .findAll();

                if (txs != null && txs.size() > 0)
                {
                    txs.deleteAllFromRealm();
                }

                resetBlockRead(r, chainId, walletAddress);
            });
        }
        catch (Exception e)
        {
            //
        }
    }

    private void resetBlockRead(Realm instance, int chainId, String walletAddress)
    {
        try
        {
            instance.executeTransactionAsync(r -> {
                RealmToken realmToken = r.where(RealmToken.class)
                        .equalTo("address", databaseKey(chainId, walletAddress))
                        .equalTo("chainId", chainId)
                        .findFirst();

                if (realmToken != null)
                {
                    realmToken.setEarliestTransactionBlock(0);
                    realmToken.setLastBlock(0);
                    realmToken.setLastTxTime(0);
                }
            });
        }
        catch (Exception e)
        {
            //
        }
    }

    private void writeEvents(Realm instance, EtherscanEvent[] events, String walletAddress, @NonNull NetworkInfo networkInfo, final boolean isNFT) throws Exception
    {
        String TO_TOKEN = "[TO_ADDRESS]";
        String FROM_TOKEN = "[FROM_ADDRESS]";
        String AMOUNT_TOKEN = "[AMOUNT_TOKEN]";
        String VALUES = "from,address," + FROM_TOKEN + ",to,address," + TO_TOKEN + ",amount,uint256," + AMOUNT_TOKEN;

        //write event list
        for (EtherscanEvent ev : events)
        {
            Transaction tx = isNFT ? ev.createNFTTransaction(networkInfo) : ev.createTransaction(networkInfo);
            //find tx name
            String activityName = tx.getEventName(walletAddress);
            String valueList = VALUES.replace(TO_TOKEN, ev.to).replace(FROM_TOKEN, ev.from).replace(AMOUNT_TOKEN,
                    (isNFT || ev.value == null) ? "1" : ev.value); //Etherscan sometimes interprets NFT transfers as FT's
            storeTransferData(instance, tx.hash, valueList, activityName, ev.contractAddress);
            //ensure we have fetched the transaction for each hash
            writeTransaction(instance, tx);
        }
    }

    private void storeTransferData(Realm instance, String hash, String valueList, String activityName, String tokenAddress) throws Exception
    {
        RealmTransfer matchingEntry = instance.where(RealmTransfer.class)
                .equalTo("hash", hash)
                .equalTo("tokenAddress", tokenAddress)
                .equalTo("eventName", activityName)
                .equalTo("transferDetail", valueList)
                .findFirst();

        if (matchingEntry == null) //prevent duplicates
        {
            instance.beginTransaction();
            RealmTransfer realmToken = instance.createObject(RealmTransfer.class);
            realmToken.setHash(hash);
            realmToken.setTokenAddress(tokenAddress);
            realmToken.setEventName(activityName);
            realmToken.setTransferDetail(valueList);
            instance.commitTransaction();
        }
        else
        {
            System.out.println("Prevented collision: " + tokenAddress);
        }
    }

    private void checkTransaction(Realm instance, Transaction tx, String walletAddress, NetworkInfo networkInfo) throws Exception
    {
        RealmTransaction matchingKey = instance.where(RealmTransaction.class)
                .equalTo("hash", tx.hash)
                .findFirst();

        if (matchingKey != null)
        {
            Transaction otx = TransactionsRealmCache.convert(matchingKey);
            if (otx.input.length() < FUNCTION_LENGTH)
            {
                instance.beginTransaction();
                matchingKey.deleteFromRealm();
                instance.commitTransaction();
                matchingKey = null;
            }
        }

        if (matchingKey == null)
        {
            fetchAndStoreTransaction(walletAddress, tx.hash, networkInfo.chainId, tx.timeStamp);
        }
    }

    public void fetchAndStoreTransaction(String walletAddress, String txHash, int chainId, long txTime)
    {
        Web3j web3j = getWeb3jService(chainId);
        EventUtils.getTransactionDetails(txHash, web3j)
                .map(ethTx -> new Transaction(ethTx.getResult(), chainId, true, txTime))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(tx -> writeTransaction(walletAddress, tx), Throwable::printStackTrace)
                .isDisposed();
    }

    private void writeTransaction(String walletAddress, Transaction tx)
    {
        try (Realm instance = realmManager.getRealmInstance(walletAddress))
        {
            instance.beginTransaction();
            RealmTransaction realmTx = instance.where(RealmTransaction.class)
                    .equalTo("hash", tx.hash)
                    .findFirst();

            if (realmTx == null) realmTx = instance.createObject(RealmTransaction.class, tx.hash);
            TransactionsRealmCache.fill(instance, realmTx, tx);
            instance.commitTransaction();
        }
        catch (Exception e)
        {
            //
        }
    }

    private void writeTransaction(Realm instance, Transaction tx)
    {
        instance.executeTransactionAsync(r -> {
            RealmTransaction realmTx = r.where(RealmTransaction.class)
                    .equalTo("hash", tx.hash)
                    .findFirst();
            if (realmTx == null)
            {
                realmTx = r.createObject(RealmTransaction.class, tx.hash);
            }

            if (realmTx.getInput() == null || realmTx.getInput().length() <= 10)
            {
                TransactionsRealmCache.fill(r, realmTx, tx);
            }
        });
    }


    /**
     * These functions are experimental, for discovering and populating NFT's without opensea.
     * So far the experiment appears to be working correctly,
     *
     * Tested: rapid discovery and update of tokens without opensea.
     * Once opensea is on, tokens are updated correctly.
     *
     * If tokens already discovered from opensea then we don't replace them here.
     */
    private Map<String, List<EtherscanEvent>> getEventMap(EtherscanEvent[] events)
    {
        Map<String, List<EtherscanEvent>> eventMap = new HashMap<>();
        for (EtherscanEvent ev : events)
        {
            List<EtherscanEvent> thisEventList = eventMap.get(ev.contractAddress);
            if (thisEventList == null)
            {
                thisEventList = new ArrayList<>();
                eventMap.put(ev.contractAddress, thisEventList);
            }

            thisEventList.add(ev);
        }

        return eventMap;
    }

    private Token tokenFromRealm(RealmToken realmItem, NetworkInfo networkInfo, String walletAddress)
    {
        TokenFactory tf = new TokenFactory();
        TokenInfo tInfo = tf.createTokenInfo(realmItem);
        Token result = tf.createToken(tInfo, realmItem, realmItem.getUpdateTime(), networkInfo.getShortName());
        result.setTokenWallet(walletAddress);
        return result;
    }

    private Asset parseTokenMetadata(Token newToken, BigInteger tokenId)
    {
        //1. get TokenURI
        Web3j web3j = TokenRepository.getWeb3jService(newToken.tokenInfo.chainId);
        String responseValue = callSmartContractFunction(web3j, getTokenURI(tokenId), newToken.getAddress(), newToken.getWallet());
        if (responseValue == null) responseValue = callSmartContractFunction(web3j, getTokenURI2(tokenId), newToken.getAddress(), newToken.getWallet());
        JSONObject metaData = loadMetaData(responseValue);
        if (metaData != null)
        {
            return Asset.fromMetaData(metaData, tokenId.toString(), newToken);
        }

        return null;
    }

    private JSONObject loadMetaData(String tokenURI)
    {
        JSONObject metaData = null;

        try
        {
            Request request = new Request.Builder()
                    .url(Utils.parseIPFS(tokenURI))
                    .get()
                    .build();

            okhttp3.Response response = httpClient.newCall(request).execute();
            metaData = new JSONObject(response.body().string());
        }
        catch (Exception e)
        {
            //
        }

        return metaData;
    }

    private static Function getTokenURI(BigInteger tokenId)
    {
        return new Function("tokenURI",
                Arrays.asList(new Uint256(tokenId)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Utf8String>() {}));
    }

    private static Function getTokenURI2(BigInteger tokenId)
    {
        return new Function("uri",
                Arrays.asList(new Uint256(tokenId)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Utf8String>() {}));
    }

    private BigInteger getTokenId(String tokenID)
    {
        BigInteger tokenIdBI;
        try
        {
            tokenIdBI = new BigInteger(tokenID);
        }
        catch (Exception e)
        {
            tokenIdBI = BigInteger.valueOf(-1);
        }

        return tokenIdBI;
    }

    private ERC721Token createNewERC721Token(EtherscanEvent ev, NetworkInfo networkInfo, String walletAddress, boolean knownERC721)
    {
        TokenInfo info = new TokenInfo(ev.contractAddress, ev.tokenName, ev.tokenSymbol, 0, true, networkInfo.chainId);
        ERC721Token newToken = new ERC721Token(info, null, 0, networkInfo.getShortName(), knownERC721 ? ContractType.ERC721 : ContractType.ERC721_UNDETERMINED);
        newToken.setTokenWallet(walletAddress);
        return newToken;
    }

    private String callSmartContractFunction(Web3j web3j,
                                             Function function, String contractAddress, String walletAddr)
    {
        String encodedFunction = FunctionEncoder.encode(function);

        try
        {
            org.web3j.protocol.core.methods.request.Transaction transaction
                    = createEthCallTransaction(walletAddr, contractAddress, encodedFunction);
            EthCall response = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST).send();

            List<Type> responseValues = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());

            if (!responseValues.isEmpty())
            {
                return responseValues.get(0).getValue().toString();
            }
        }
        catch (Exception e)
        {
            //
        }

        return null;
    }
}