# 交换机类型

1. fanout：广播交换机，把消息广播到所有绑定的交换机。
2. direct：定向交换机，绑定时必须填写 bingingkey。
3. topic:routingkey 可以是多个单词，用.隔开，绑定时可以用通配符
   china.weather
   china.news
   US.weather
   US.news
   可以绑定为：US# 或者 #.news

# 消息确认机制

1. **普通确认模式**：生产者每发送一条消息后，调用 waitForConfirms() 方法等待 Broker 返回确认结果，只有收到确认后才发送下一条消息。这种方式能确保消息的可靠性，但由于是同步等待，会严重影响发送性能。
2. **批量确认模式**：生产者发送一批消息后，调用 waitForConfirms() 方法等待 Broker 对这批消息的确认。相比普通确认模式，减少了等待时间，提升了发送性能，但如果有一条消息发送失败，需要重新发送这一批次的所有消息。
3. **异步确认模式**：生产者通过添加监听器 addConfirmListener() ，当 Broker 确认消息后，会异步回调监听器的 handleAck() 方法；如果消息发送失败，会回调 handleNack() 方法。这种方式既保证了消息的可靠性，又具有较好的性能。
