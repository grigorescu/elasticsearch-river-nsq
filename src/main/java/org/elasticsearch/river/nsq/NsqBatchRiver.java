package org.elasticsearch.river.nsq;

import ly.bit.nsq.Message;
import ly.bit.nsq.exceptions.NSQException;
import ly.bit.nsq.syncresponse.SyncResponseHandler;
import ly.bit.nsq.syncresponse.SyncResponseReader;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.XContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("unused")
public class NsqBatchRiver extends AbstractRiverComponent implements River {

    private final Client client;

    private final String nsqAddress;
    private final int nsqPort;
    private final String nsqTopic;
    private final String nsqChannel;
    
    private final int bulkSize;
	private final TimeValue bulkTimeout;
    private final boolean ordered;

    private volatile boolean closed = false;
    
    private final AtomicInteger onGoingBulks = new AtomicInteger();

    private volatile BulkRequestBuilder currentRequest;

    private volatile Thread thread;

    @SuppressWarnings({"unchecked"})
    @Inject
    public NsqBatchRiver(RiverName riverName, RiverSettings settings, Client client) {
        super(riverName, settings);
        this.client = client;

        if (settings.settings().containsKey("nsq")) {
            Map<String, Object> nsqSettings = (Map<String, Object>) settings.settings().get("nsq");
            nsqAddress = XContentMapValues.nodeStringValue(nsqSettings.get("address"), "localhost");
            nsqPort = XContentMapValues.nodeIntegerValue(nsqSettings.get("port"), 4150);
            nsqTopic = XContentMapValues.nodeStringValue(nsqSettings.get("topic"), "elasticsearch");
            nsqChannel = XContentMapValues.nodeStringValue(nsqSettings.get("channel"), "elasticsearch");            
        } else {
            nsqAddress = "localhost";
            nsqPort = 4050;
            nsqTopic = "elasticsearch";
            nsqChannel = "elasticsearch";
        }

        if (settings.settings().containsKey("index")) {
        	Map<String, Object> indexSettings = (Map<String, Object>) settings.settings().get("index");
            bulkSize = XContentMapValues.nodeIntegerValue(indexSettings.get("bulk_size"), 100);
            if (indexSettings.containsKey("bulk_timeout")) {
                bulkTimeout = TimeValue.parseTimeValue(XContentMapValues.nodeStringValue(indexSettings.get("bulk_timeout"), "10ms"), TimeValue.timeValueMillis(10));
            } else {
                bulkTimeout = TimeValue.timeValueMillis(10);
            }
            ordered = XContentMapValues.nodeBooleanValue(indexSettings.get("ordered"), false);
        } else {
            bulkSize = 100;
            bulkTimeout = TimeValue.timeValueMillis(10);
            ordered = false;
        }
    }

    @Override
    public void start() {
    	currentRequest = client.prepareBulk();
        thread = EsExecutors.daemonThreadFactory(settings.globalSettings(), "nsq_river").newThread(new Consumer());
        thread.start();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        logger.info("closing nsq river");
        closed = true;
        thread.interrupt();
    }

    private class NsqHandler implements SyncResponseHandler {
    	public boolean handleMessage(Message msg) throws NSQException {
    		try {
    			String data = new String(msg.getBody());
    			int i = data.indexOf('}') + 1;
    			String currentFieldName = null;
                String index = null;
                String type = null;
                String id = null;
    			XContent xContent = XContentFactory.xContent(data.getBytes(), 0, i+1);
    			XContentParser parser = xContent.createParser(data.getBytes(), 0, i+1);
    			XContentParser.Token token = parser.nextToken();
                assert token == XContentParser.Token.START_OBJECT;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        currentFieldName = parser.currentName();
                    } else if (token.isValue()) {
                        if ("_index".equals(currentFieldName)) {
                            index = parser.text();
                        } else if ("_type".equals(currentFieldName)) {
                            type = parser.text();
                        }
                        else if ("_id".equals(currentFieldName)) {
                        	id = parser.text();
                        }
                    }
                }
                byte[] source = Arrays.copyOfRange(data.getBytes(), i+1, data.length());
                source[0] = '{';
                if (id == null) {
                	currentRequest.add(client.prepareIndex(index, type).setSource(source));
                }
                else {
                	currentRequest.add(client.prepareIndex(index, type, id).setSource(source));
                }
			} catch (Exception e) {
				logger.info("Could not parse data.");
				e.printStackTrace();
			}
            processBulkIfNeeded();
    		return true;
    	}
    	
    	 private void processBulkIfNeeded() {
             if (currentRequest.numberOfActions() >= bulkSize) {
            	 // execute the bulk operation
                 int currentOnGoingBulks = onGoingBulks.incrementAndGet();
                 try {
                     currentRequest.execute(new ActionListener<BulkResponse>() {
                         @Override
                         public void onResponse(BulkResponse bulkResponse) {
                             onGoingBulks.decrementAndGet();
                         }

                         @Override
                         public void onFailure(Throwable e) {
                             onGoingBulks.decrementAndGet();
                             logger.warn("Failed to execute bulk");
                             e.printStackTrace();
                         }
                     });
                 } catch (Exception e) {
                     onGoingBulks.decrementAndGet();
                     logger.warn("Failed to process bulk", e);
                     e.printStackTrace();
                 }
             currentRequest = client.prepareBulk();
             }
         }
     }
    
    private class Consumer implements Runnable {

        @Override
        public void run() {
        	SyncResponseHandler handler = new NsqHandler();
        	SyncResponseReader reader = new SyncResponseReader(nsqTopic, nsqChannel, handler);
            logger.info("creating nsq river, address [{}] [{}] [{}] [{}]", nsqAddress, nsqPort, nsqTopic, nsqChannel);
            
            try {
            	reader.connectToNsqd(nsqAddress, nsqPort);
        		} catch (NSQException e) {
    			e.printStackTrace();
    		}
    		try { Thread.sleep(5000); } 
    		catch (InterruptedException e) { e.printStackTrace(); }
        }
    }
}
