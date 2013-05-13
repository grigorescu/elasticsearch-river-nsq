NSQ River Plugin for Bro + ElasticSearch
========================================

Note: _This is a special-case of the more general NSQ river plugin, designed for use with the Bro Network Security Monitor._

When sending Bro logs to ElasticSearch, the ElasticSearch cluster can fall behind under heavy load. Instead of this traditional "push" model, an ElasticSearch river is a plugin for ElasticSearch, that "pulls" data when ElasticSearch is ready for more. This plugin has ElasticSearch pull data from NSQ - an HTTP queue designed and used by bit.ly.

To use this, you'll need to setup NSQ and use the Bro Log::WRITER_ELASTICSEARCH_NSQ log writer.

## NSQ Installation

* Grab a binary from here: https://github.com/bitly/nsq/blob/master/INSTALLING.md#binary (TODO: Manual installation instructions)
* Extract and cd to the directory.
* Note: These commands will send all log output to a file. For debugging, you might want to keep them in the foreground.
* ```./bin/nsqlookupd &>> nslookupd.log &```
* ```./bin/nsqd --lookupd-tcp-address=127.0.0.1:4160 &>> nsqd.log &```
* ```./bin/nsqadmin --lookupd-http-address=127.0.0.1:4161 --template-dir=share/nsqadmin/templates/ &>> nsqadmin.log &```
* At this point, if you visit 127.0.0.1:4171, you should see the NSQ admin interface. Note: the web interface needs to load JQuery and Bootstrap from the Internet, so your browser will need access to the Internet.

## ElasticSearch Installation

* See: http://git.bro.org/bro.git/blob/HEAD:/doc/logging-elasticsearch.rst and https://gist.github.com/grigorescu/4237360 for basic install instrutions.
* From your ElasticSearch directory, run: ./bin/plugin -install grigorescu/elasticsearch-river-nsq-plugin
* If your system doesn't have Internet access, download the zip from: https://github.com/grigorescu/elasticsearch-river-nsq-plugin/archive/master.zip and run: ./bin/plugin -install elasticsearch-river-nsq -url file:///home/joe_user/elasticsearch-river-nsq-plugin-master.zip 
* Finally, to get ElasticSearch to use the plugin, run:

```shell
curl -XPUT 'localhost:9200/_river/nsqd/_meta' -d '{              
    "type" : "nsq",
    "nsq" : {
        "topic" : "bro-logs",
        "channel" : "elasticsearch"
    },
    "index" : {
        "bulk_size" : 10000
   }
}'
```

## Bro Installation

* To use it in Bro, run something like this:

```bro
@load base/frameworks/logging/writers/elasticsearch_nsq

event bro_init() &priority=-5
        {
        for ( stream_id in Log::active_streams )
                {
                if ( stream_id in LogElasticSearchNSQ::excluded_log_ids ||
                     (|LogElasticSearchNSQ::send_logs| > 0 && stream_id !in LogElasticSearchNSQ::send_logs) )
                        next;

                local filter: Log::Filter = [$name = "nsq-test",
                                             $writer = Log::WRITER_ELASTICSEARCH_NSQ,
                                             $interv = LogElasticSearchNSQ::rotation_interval];
                Log::add_filter(stream_id, filter);
                }
        }
```