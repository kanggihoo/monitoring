-- 거래 유니버스 28종 시드. (S15P21A605-50)
--
-- 원본: 뉴스 데이터 패키지 v1.1 (flashback_news_v1.1/data/instruments.json, 2026-09-07).
-- v1.1 은 종목명·티커를 전부 가상화했다 — 실명·수집 심볼은 패키지 내부 기록이며 여기 넣지
-- 않는다. docs/projects/종목.md 의 실명 목록과 표시명이 다른 이유가 이것이고, 실명/가상명
-- 확정은 별도 결정이다. 확정되면 UPDATE 마이그레이션으로 name 만 바꾸면 된다.
--
-- id 를 1~28 로 명시한다. 순서는 종목.md 의 번호(시장 → 구분 순)와 같고, V12 의 뉴스 링크가
-- 이 번호를 참조한다. 유니버스는 시즌 전체에서 고정이라 애플리케이션이 행을 추가하지
-- 않지만, 혹시를 위해 IDENTITY 를 29 부터 재시작해 둔다.
--
-- summary 는 정보 카드용 1줄 개요를 여기서 적었다 (기획서 9.1.1). info_card(ETF·코인의
-- 상품·프로젝트 카드)는 별도 콘텐츠라 NULL 로 두고, 그 작업에서 채운다.
--
-- listed_game_date 는 IPO 8종의 실제 상장일이며 뉴스 데이터의 상장 기사 날짜와 같다.
-- 기획서 IPO 편성표의 상장 게임월과 같은 달이다. 게임 시계가 12분 그리드의 월 시작 틱으로
-- 상장을 편성한다면 그 작업에서 이 값을 재도출한다.

INSERT INTO instruments (id, symbol, name, category, asset_type, currency, price_scale, listed_game_date, summary)
VALUES
(1, 'NQX', 'NQX 100 ETF', 'NASDAQ', 'ETF', 'USD', 2, NULL, '나스닥 100 지수를 추종하는 대표 ETF. 대형 기술주 비중이 높다.'),
(2, 'SNQX', 'NQX 인버스 3X', 'NASDAQ', 'INVERSE_ETF', 'USD', 2, NULL, '나스닥 100 지수 일간 수익률의 -3배를 추종하는 인버스 레버리지 ETF.'),
(3, 'SPRB', '수페르비아', 'NASDAQ', 'STOCK', 'USD', 2, NULL, 'GPU 설계 기업. 데이터센터·게임용 반도체를 만든다.'),
(4, 'COIL', '코일모터스', 'NASDAQ', 'STOCK', 'USD', 2, NULL, '전기차와 에너지 저장 장치를 만드는 기업. 자율주행 소프트웨어를 함께 개발한다.'),
(5, 'USVR', '울트라서버', 'NASDAQ', 'STOCK', 'USD', 2, NULL, 'AI·데이터센터용 서버를 설계·제조하는 기업.'),
(6, 'BOOM', '붐 비디오', 'NASDAQ', 'STOCK', 'USD', 2, NULL, '화상회의 소프트웨어 기업. 재택근무 확산으로 이용자가 급증했다.'),
(7, 'MACR', '매크로스트래티지', 'NASDAQ', 'STOCK', 'USD', 2, NULL, '기업용 분석 소프트웨어 기업. 비트코인을 대량 보유하는 재무 전략으로 알려졌다.'),
(8, 'RBGN', '리보젠', 'NASDAQ', 'STOCK', 'USD', 2, NULL, 'mRNA 기반 백신·치료제를 개발하는 바이오 기업.'),
(9, 'CNHR', '코인하버', 'NASDAQ', 'IPO_STOCK', 'USD', 2, DATE '2021-04-14', '미국 최대 가상자산 거래소 운영 기업. 거래 수수료가 주 수입원이다.'),
(10, 'CNYN', '캐니언모터스', 'NASDAQ', 'IPO_STOCK', 'USD', 2, DATE '2021-11-10', '전기 픽업트럭·SUV를 만드는 신생 전기차 기업.'),
(11, 'ARCX', '아키텍스', 'NASDAQ', 'IPO_STOCK', 'USD', 2, DATE '2023-09-14', '모바일 칩의 표준 아키텍처를 설계하고 라이선스로 수익을 얻는 반도체 설계 기업.'),
(12, 'GRDW', '그리드위브', 'NASDAQ', 'IPO_STOCK', 'USD', 2, DATE '2025-03-28', 'GPU 클라우드 인프라를 임대하는 AI 데이터센터 기업.'),
(13, 'KTOP', 'KTOP 200', 'KRX', 'ETF', 'KRW', 0, NULL, '코스피 200 지수를 추종하는 대표 ETF.'),
(14, 'KTIV', 'KTOP 200 인버스2X', 'KRX', 'INVERSE_ETF', 'KRW', 0, NULL, '코스피 200 선물 일간 수익률의 -2배를 추종하는 인버스 레버리지 ETF.'),
(15, 'HSEL', '한성전자', 'KRX', 'STOCK', 'KRW', 0, NULL, '메모리 반도체·스마트폰·가전을 아우르는 국내 최대 전자 기업.'),
(16, 'HPNX', '하이퍼닉스', 'KRX', 'STOCK', 'KRW', 0, NULL, '메모리 반도체 기업. HBM 등 AI 서버용 메모리를 공급한다.'),
(17, 'GVLT', '그린볼트', 'KRX', 'STOCK', 'KRW', 0, NULL, '이차전지 양극재 소재 계열사를 거느린 지주회사.'),
(18, 'HRNG', '화랑에어로스페이스', 'KRX', 'STOCK', 'KRW', 0, NULL, '항공 엔진·방산·우주 사업을 하는 기업. 자주포·장갑차를 수출한다.'),
(19, 'COCO', '코코아', 'KRX', 'STOCK', 'KRW', 0, NULL, '메신저를 기반으로 광고·커머스·콘텐츠·핀테크 사업을 하는 플랫폼 기업.'),
(20, 'OCSN', '오션상선', 'KRX', 'STOCK', 'KRW', 0, NULL, '국내 최대 컨테이너 해운사. 운임 지수에 따라 실적이 크게 움직인다.'),
(21, 'CCGM', '코코아게임즈', 'KRX', 'IPO_STOCK', 'KRW', 0, DATE '2020-09-10', '모바일·PC 게임 퍼블리싱 기업. 플랫폼 기업의 게임 자회사다.'),
(22, 'MKST', '메이커스톤', 'KRX', 'IPO_STOCK', 'KRW', 0, DATE '2021-08-10', '배틀로얄 슈팅 게임으로 성장한 게임 개발사.'),
(23, 'GSRB', '거산로보틱스', 'KRX', 'IPO_STOCK', 'KRW', 0, DATE '2023-10-05', '협동로봇을 개발·제조하는 로봇 기업.'),
(24, 'NRCS', '누리CNS', 'KRX', 'IPO_STOCK', 'KRW', 0, DATE '2025-02-05', 'IT 서비스·클라우드·스마트팩토리 사업을 하는 시스템 통합 기업.'),
(25, 'ETRA', '에테라', 'CRYPTO', 'CRYPTO', 'USD', 2, NULL, '스마트 컨트랙트 플랫폼의 기축 코인. 디파이·NFT 생태계의 결제·수수료 수단이다.'),
(26, 'CNTA', '커넥타', 'CRYPTO', 'CRYPTO', 'USD', 2, NULL, '블록체인 외부 데이터를 연결하는 오라클 네트워크의 코인.'),
(27, 'HEXA', '헥사', 'CRYPTO', 'CRYPTO', 'USD', 4, NULL, '이더리움 확장(레이어2) 네트워크의 코인. 소액 단위로 거래된다.'),
(28, 'LAND', '메타랜드', 'CRYPTO', 'CRYPTO', 'USD', 4, NULL, '가상 부동산 메타버스 플랫폼의 코인.');

ALTER TABLE instruments ALTER COLUMN id RESTART WITH 29;
