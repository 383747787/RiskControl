package clickstream

import kafka.serializer.StringDecoder
import net.sf.json.JSONObject
import org.apache.hadoop.hbase.client.{HTable, Put}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.{HBaseConfiguration, TableName}
import org.apache.spark.SparkConf
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.{Seconds, StreamingContext}

/**
  * Created by 郭飞 on 2016/5/31.
  */
object PageViewStream {

  def main(args: Array[String]): Unit = {
    var masterUrl = "local[2]"
    if (args.length > 0) {
      masterUrl = args(0)
    }

    // Create a StreamingContext with the given master URL
    val conf = new SparkConf().setMaster(masterUrl).setAppName("PageViewStream")
    val ssc = new StreamingContext(conf, Seconds(5))

    // Kafka configurations
    //val topics = Set("PageViewStream")
    val topics = Set("user_events")
    //本地虚拟机ZK地址
    //val brokers = "hadoop1:9092,hadoop2:9092,hadoop3:9092"
    val brokers = "hc4:9092"
    val kafkaParams = Map[String, String](
      "metadata.broker.list" -> brokers,
      "serializer.class" -> "kafka.serializer.StringEncoder")

    // Create a direct stream
    val kafkaStream = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](ssc, kafkaParams, topics)

    val events = kafkaStream.flatMap(line => {
      val data = JSONObject.fromObject(line._2)
      Some(data)
    })

    // Compute user click times
    val userClicks = events.map(x => (x.getString("uid"), x.getInt("click_count"))).reduceByKey(_ + _)
    userClicks.foreachRDD(rdd => {
      rdd.foreachPartition(partitionOfRecords => {
        //Hbase配置
        val tableName = "PageViewStream"
        val hbaseConf = HBaseConfiguration.create()
        //hbaseConf.set("hbase.zookeeper.quorum", "hadoop1:9092")
        hbaseConf.set("hbase.zookeeper.quorum", "hc4:9092")
        hbaseConf.set("hbase.zookeeper.property.clientPort", "2181")
        hbaseConf.set("hbase.defaults.for.version.skip", "true")

        partitionOfRecords.foreach(pair => {
          //用户ID
          val uid = pair._1
          //点击次数
          val click = pair._2
          System.out.println("uid: "+uid+" click: "+click)
          //组装数据  create 'PageViewStream','Stat'
          val put = new Put(Bytes.toBytes(uid))
          put.add("Stat".getBytes, "ClickStat".getBytes, Bytes.toBytes(click))
          val StatTable = new HTable(hbaseConf, TableName.valueOf(tableName))
          StatTable.setAutoFlush(false, false)
          //写入数据缓存
          StatTable.setWriteBufferSize(3*1024*1024)
          StatTable.put(put)
          //提交
          StatTable.flushCommits()
        })
      })
    })
    ssc.start()
    ssc.awaitTermination()

  }
}
