-- 앱이 읽어 주는 말 빠르기(iOS 기준 0.5가 보통, 앱 기본 0.42). 다른 폰에서 로그인해도 같은 빠르기로 말하려고 저장. null이면 앱 기본값.
ALTER TABLE profiles ADD COLUMN speech_rate numeric(3,2);
