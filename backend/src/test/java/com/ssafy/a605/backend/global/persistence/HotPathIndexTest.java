package com.ssafy.a605.backend.global.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.support.ContainersConfig;

/**
 * 매 틱 · 매 화면마다 도는 조회가 인덱스를 <b>탈 수 있는가</b>를 본다 (V29, S15P21A605-160).
 *
 * <p><b>{@code ddl-auto: validate} 는 인덱스를 보지 않는다.</b> 컬럼과 타입만 대조하므로
 * 인덱스가 빠져도, 컬럼 순서가 정렬과 어긋나도, 부분 인덱스의 조건이 질의와 달라도 기동은
 * 성공한다. 그 세 가지가 이 테스트가 잡는 것이다.
 *
 * <p><b>"탄다"가 아니라 "탈 수 있다"를 본다.</b> 테스트 DB 는 행이 몇 개라 플래너가 인덱스가
 * 있어도 Seq Scan 을 고른다 — 그 선택은 옳고, 인덱스의 결함이 아니다. 그래서 {@code enable_seqscan}
 * 을 끄고 EXPLAIN 한다. 그 상태에서도 Seq Scan 이 나오면 인덱스가 그 질의에 <b>쓰일 수 없다</b>는
 * 뜻이고, 그것이 잡아야 할 결함이다 — 부분 인덱스의 WHERE 가 질의의 WHERE 와 다르면 정확히
 * 그렇게 된다.
 *
 * <p>질의는 리포지토리 메서드가 실제로 만드는 SQL 의 모양을 그대로 적었다. 메서드 이름이
 * 바뀌어 조건이 달라지면 여기도 함께 바뀌어야 한다.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=disabled",
                "app.replay.fixture.enabled=false",
                "app.test-support.enabled=false"
        })
@Import(ContainersConfig.class)
@Transactional
class HotPathIndexTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("orders_OPEN 부분 인덱스_존재하고 조건이 OPEN 이다")
    void ordersOpenIndex_existsAsPartialIndex() {
        String definition = indexDefinition("ix_orders_open");
        assertThat(definition).contains("ON public.orders USING btree (status)")
                .contains("WHERE ((status)::text = 'OPEN'::text)");
    }

    @Test
    @DisplayName("notifications 인덱스_존재하고 컬럼 순서가 목록 정렬과 같다")
    void notificationsIndex_matchesListOrdering() {
        // NotificationController.LATEST_FIRST = created_at DESC, id DESC. 어긋나면 Sort 노드가 남는다.
        assertThat(indexDefinition("ix_notifications_account_latest"))
                .contains("(account_id, created_at DESC, id DESC)");
    }

    /** OrderRepository.findByStatus(OPEN) 이 만드는 질의. 매 틱 체결 판정의 첫 단계다. */
    @Test
    @DisplayName("findByStatus(OPEN)_인덱스를 탈 수 있다")
    void findOpenOrders_canUseIndex() {
        assertThat(planWithoutSeqScan("SELECT * FROM orders WHERE status = 'OPEN'"))
                .contains("ix_orders_open");
    }

    /** NotificationRepository.countByAccountIdAndReadAtIsNull 이 만드는 질의. 헤더 배지다. */
    @Test
    @DisplayName("미읽음 카운트_인덱스를 탈 수 있다")
    void unreadCount_canUseIndex() {
        assertThat(planWithoutSeqScan(
                "SELECT count(*) FROM notifications WHERE account_id = 1 AND read_at IS NULL"))
                .contains("ix_notifications_account_latest");
    }

    /** NotificationRepository.findByAccountId(pageable) 이 만드는 질의. 정렬까지 인덱스가 먹어야 한다. */
    @Test
    @DisplayName("알림 목록 최신순_인덱스를 타고 별도 정렬이 없다")
    void notificationList_usesIndexWithoutSort() {
        String plan = planWithoutSeqScan(
                "SELECT * FROM notifications WHERE account_id = 1 ORDER BY created_at DESC, id DESC LIMIT 20");
        assertThat(plan).contains("ix_notifications_account_latest");
        assertThat(plan).as("인덱스 순서가 정렬과 같으면 Sort 노드가 필요 없다").doesNotContain("Sort");
    }

    private String indexDefinition(String indexName) {
        List<String> rows = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = :name")
                .param("name", indexName)
                .query(String.class)
                .list();
        assertThat(rows).as("인덱스 %s 가 없다", indexName).hasSize(1);
        return rows.getFirst();
    }

    /**
     * Seq Scan 을 금지한 상태의 실행 계획. 인덱스가 그 질의에 쓰일 수 있으면 여기서 나타나고,
     * 쓰일 수 없으면 플래너가 벌점을 감수하고도 Seq Scan 을 낸다.
     */
    private String planWithoutSeqScan(String sql) {
        // 트랜잭션이 끝나면 함께 풀린다. RESET 을 따로 두지 않는 이유다.
        jdbc.sql("SET LOCAL enable_seqscan = off").update();
        return String.join("\n", jdbc.sql("EXPLAIN " + sql).query(String.class).list());
    }
}
