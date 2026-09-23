package com.ssafy.a605.backend.domain.notification.repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.a605.backend.domain.notification.entity.NotificationKind;

import lombok.RequiredArgsConstructor;

/**
 * 같은 문구의 알림을 여러 계좌에 <b>한 문장으로</b> 넣는다. 전원 공지가 쓴다
 * ({@code NotificationService.createForAll}).
 *
 * <p><b>JPA 로는 한 번에 넣을 수 없다.</b> {@code notifications.id} 가 {@code IDENTITY} 라
 * Hibernate 가 INSERT 를 JDBC 배치로 묶지 못하고 — 식별자를 받으려면 문장마다 실행해야 한다 —
 * {@code saveAll} 은 계좌 수만큼의 왕복이 된다. 전원 공지는 사람 수가 그대로 왕복 수가 되는
 * 유일한 경로라 여기만 SQL 로 내려간다.
 *
 * <p><b>계좌 목록을 배열 하나로 보낸다.</b> {@code VALUES} 를 사람 수만큼 이어 붙이면 제목과
 * 본문이 행마다 복사돼 나가고 바인드 파라미터 상한(65535)에도 걸린다. {@code unnest} 는 공통
 * 값을 한 번만 싣는다.
 *
 * <p><b>{@code account_id} 를 함께 돌려받는다.</b> {@code RETURNING} 의 행 순서는 보장되지
 * 않으므로 짝은 값으로 맞춘다 — 순서를 믿으면 남의 알림 식별자를 그 사람에게 보내게 된다.
 */
@Repository
@RequiredArgsConstructor
public class NotificationBulkRepository {

    private static final String INSERT = """
            INSERT INTO notifications (account_id, kind, title, body, reference_id, tick_seq, created_at)
            SELECT account_id, ?, ?, ?, ?, ?, ? FROM unnest(?) AS account_id
            RETURNING id, account_id
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param accountIds  받는 계좌. 비어 있지 않다
     * @param kind        유형
     * @param title       발생 시점에 확정한 표기 문구
     * @param body        발생 시점에 확정한 본문
     * @param referenceId 이동할 대상의 식별자. 고정 화면으로 가는 유형이면 {@code null}
     * @param tickSeq     발생 시점의 게임 시각. 틱에 속하지 않는 사건이면 {@code null}
     * @param createdAt   발생 실제 시각
     * @return 계좌별로 만들어진 알림 식별자
     */
    public Map<Long, Long> insertAll(List<Long> accountIds, NotificationKind kind, String title, String body,
                                     Long referenceId, Long tickSeq, Instant createdAt) {
        List<long[]> rows = jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(INSERT);
            statement.setString(1, kind.name());
            statement.setString(2, title);
            statement.setString(3, body);
            statement.setObject(4, referenceId, java.sql.Types.BIGINT);
            statement.setObject(5, tickSeq, java.sql.Types.BIGINT);
            statement.setTimestamp(6, Timestamp.from(createdAt));
            statement.setArray(7, connection.createArrayOf("bigint", accountIds.toArray()));
            return statement;
        }, (resultSet, rowNumber) -> new long[] {resultSet.getLong("account_id"), resultSet.getLong("id")});

        Map<Long, Long> ids = new HashMap<>();
        for (long[] row : rows) {
            ids.put(row[0], row[1]);
        }
        return ids;
    }
}
