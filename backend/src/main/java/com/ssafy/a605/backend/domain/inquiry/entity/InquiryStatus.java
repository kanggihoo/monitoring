package com.ssafy.a605.backend.domain.inquiry.entity;

/**
 * 문의의 상태. 답변 전과 답변 후 둘뿐이며 중간 상태를 만들지 않는다.
 *
 * <p>답변이 달렸는지와 같은 뜻이라 별도로 관리하지 않는다 — 답변 등록이 상태 전이를 함께 일으킨다.
 */
public enum InquiryStatus {

    /** 답변 전. 이 상태에서만 작성자가 삭제할 수 있다. */
    OPEN,

    /** 답변 후. */
    ANSWERED
}
