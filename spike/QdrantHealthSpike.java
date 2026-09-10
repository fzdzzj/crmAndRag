import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.qdrant.client.grpc.QdrantGrpc;
import io.qdrant.client.grpc.QdrantOuterClass;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 原生 gRPC 健康探测样例：只验证 6334 的 qdrant.Qdrant/HealthCheck 能力。
 *
 * <p>不改业务代码、不加依赖；探测失败时抛出异常，供人工确认错误类型。</p>
 */
public final class QdrantHealthSpike {

    /** 防止工具类被实例化。 */
    private QdrantHealthSpike() {
    }

    /**
     * 程序入口。
     *
     * @param args 未使用
     * @throws InterruptedException 关闭 channel 被中断时抛出
     */
    public static void main(String[] args) throws InterruptedException {
        String host = System.getenv().getOrDefault("QDRANT_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("QDRANT_GRPC_PORT", "6334"));
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        try {
            QdrantGrpc.QdrantBlockingStub stub = QdrantGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(2, TimeUnit.SECONDS);
            QdrantOuterClass.HealthCheckReply reply =
                    stub.healthCheck(QdrantOuterClass.HealthCheckRequest.getDefaultInstance());
            if (reply.getTitle().isBlank() || reply.getVersion().isBlank()) {
                throw new IllegalStateException("gRPC 健康响应缺少 title/version");
            }
            System.out.println("qdrant grpc health: PASS, title=" + reply.getTitle()
                    + ", version=" + reply.getVersion());
        }
        finally {
            channel.shutdown();
            if (!channel.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS)) {
                channel.shutdownNow();
            }
        }
    }
}
