package space.lichuang.rocketmq.producer.controller;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientConfigurationBuilder;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.apache.rocketmq.shaded.com.google.common.base.Strings;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;


@RestController
@RequestMapping("rocketmq")
@Slf4j
public class ProducerMsgController {

    /**
     * 发送普通消息
     */
    @PostMapping("/msg")
    public void sendMsg(){
        // 接入点地址，需要设置成Proxy的地址和端口列表，一般是xxx:8081;xxx:8081。
        String endpoint = "localhost:8081";
        // 消息发送的目标Topic名称，需要提前创建。
        String topic = "TestTopic";
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfigurationBuilder builder = ClientConfiguration.newBuilder().setEndpoints(endpoint);
        ClientConfiguration configuration = builder.build();
        // 初始化Producer时需要设置通信配置以及预绑定的Topic。
        Producer producer = null;
        try {
            producer = provider.newProducerBuilder()
                    .setTopics(topic)
                    .setClientConfiguration(configuration)
                    .build();
        } catch (ClientException e) {
            throw new RuntimeException(e);
        }
        // 普通消息发送。
        Message message = provider.newMessageBuilder()
                .setTopic(topic)
                // 设置消息索引键，可根据关键字精确查找某条消息。
                .setKeys("messageKey")
                // 设置消息Tag，用于消费端根据指定Tag过滤消息。
                .setTag("messageTag")
                // 消息体。
                .setBody("我是消息本体".getBytes())
                .build();
        try {
            // 发送消息，需要关注发送结果，并捕获失败等异常。
            SendReceipt sendReceipt = producer.send(message);
            log.info("Send message successfully, messageId={}", sendReceipt.getMessageId());
        } catch (ClientException e) {
            log.error("Failed to send message", e);
        }
    }

    /**
     * 发送事务消息
     */
    @PostMapping("/transactionMsg")
    public void sendTransactionMsg() throws ClientException {

        // 接入点地址，需要设置成Proxy的地址和端口列表，一般是xxx:8081;xxx:8081。
        String endpoint = "localhost:8081";
        // 消息发送的目标Topic名称，需要提前创建。
        String topic = "TransactionTopic";
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfigurationBuilder builder = ClientConfiguration.newBuilder().setEndpoints(endpoint);
        ClientConfiguration configuration = builder.build();
        //构造事务生产者：事务消息需要生产者构建一个事务检查器，用于检查确认异常半事务的中间状态。
        Producer producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTransactionChecker(messageView -> {
                    /**
                     * 事务检查器一般是根据业务的ID去检查本地事务是否正确提交还是回滚，此处以订单ID属性为例。
                     * 在订单表找到了这个订单，说明本地事务插入订单的操作已经正确提交；如果订单表没有订单，说明本地事务已经回滚。
                     */
                    final String orderId = messageView.getProperties().get("OrderId");
                    if (Strings.isNullOrEmpty(orderId)) {
                        // 错误的消息，直接返回Rollback。
                        return TransactionResolution.ROLLBACK;
                    }
                    return checkOrderById(orderId) ? TransactionResolution.COMMIT : TransactionResolution.ROLLBACK;
                })
                .build();
        //开启事务分支。
        final Transaction transaction;
        try {
            transaction = producer.beginTransaction();
        } catch (ClientException e) {
            e.printStackTrace();
            //事务分支开启失败，直接退出。
            return;
        }
        HashMap<String, Object> msgMap = new HashMap<>();
        msgMap.put("userId","1");
        msgMap.put("itemSpecId","2");
        msgMap.put("buyCounts",3);

        Message message = provider.newMessageBuilder()
                .setTopic(topic)
                //设置消息索引键，可根据关键字精确查找某条消息。
                .setKeys("messageKey")
                //设置消息Tag，用于消费端根据指定Tag过滤消息。
                .setTag("messageTag")
                //一般事务消息都会设置一个本地事务关联的唯一ID，用来做本地事务回查的校验。
                .addProperty("OrderId", "xxx")
                //消息体。
                .setBody(msgMap.toString().getBytes())
                .build();
        //发送半事务消息
        final SendReceipt sendReceipt;
        try {
            sendReceipt = producer.send(message, transaction);
        } catch (ClientException e) {
            //半事务消息发送失败，事务可以直接退出并回滚。
            return;
        }
        /**
         * 执行本地事务，并确定本地事务结果。
         * 1. 如果本地事务提交成功，则提交消息事务。
         * 2. 如果本地事务提交失败，则回滚消息事务。
         * 3. 如果本地事务未知异常，则不处理，等待事务消息回查。
         *
         */
        boolean localTransactionOk = doLocalTransaction();
        if (localTransactionOk) {
            try {
                transaction.commit();
            } catch (ClientException e) {
                // 业务可以自身对实时性的要求选择是否重试，如果放弃重试，可以依赖事务消息回查机制进行事务状态的提交。
                e.printStackTrace();
            }
        } else {
            try {
                transaction.rollback();
            } catch (ClientException e) {
                // 建议记录异常信息，回滚异常时可以无需重试，依赖事务消息回查机制进行事务状态的提交。
                e.printStackTrace();
            }
        }

    }


    //演示demo，模拟订单表查询服务，用来确认订单事务是否提交成功。
    private static boolean checkOrderById(String orderId) {
        return true;
    }

    //演示demo，模拟本地事务的执行结果。
    private static boolean doLocalTransaction() {
        return true;
    }
}
