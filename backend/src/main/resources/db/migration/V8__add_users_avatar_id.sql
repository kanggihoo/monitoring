-- 프로필 아바타. 기획서 1.1.5(가입 시 배정)·2.2(마이페이지에서 변경)가 요구하고,
-- 리더보드·채팅·IPO 공지 응답이 이미 avatarId 를 담기로 계약해 두었다.
--
-- 이미지 파일을 직접 올리지 않고 제공 세트의 식별자만 저장한다(기획서 범위 제외 목록:
-- "프로필 사진 직접 업로드"). 그래서 문자열 URL 이 아니라 작은 정수다.
--
-- DEFAULT 1 은 기존 행을 채우기 위한 것이다. 가입 시 세트 중 하나를 무작위 배정하는
-- 것은 애플리케이션의 몫이며, 아바타 세트의 크기가 정해진 뒤에 붙인다.
ALTER TABLE users
    ADD COLUMN avatar_id SMALLINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN users.avatar_id IS '제공 아바타 세트의 식별자. 리더보드·채팅·IPO 공지 응답의 avatarId. 탈퇴자의 과거 채팅에는 null 로 표시된다(조인이 비므로)';
