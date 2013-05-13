NSQ River Plugin for Bro + ElasticSearch
========================================

Note: _This is a special-case of the more general NSQ river plugin, designed for use with the Bro Network Security Monitor._

When sending Bro logs to ElasticSearch, the ElasticSearch cluster can fall behind under heavy load. Instead of this traditional "push" model, an ElasticSearch river is a plugin for ElasticSearch, that "pulls" data when ElasticSearch is ready for more. This plugin has ElasticSearch pull data from NSQ - an HTTP queue designed and used by bit.ly.

To use this, you'll need to setup NSQ and use the Bro Log::WRITER_ELASTICSEARCH_NSQ log writer.

TODO: More install instructions here.