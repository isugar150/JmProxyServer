# JmProxyServer

## 특징
JmProxyServer는 프록시 프로그램이며, 쉬운 설정, 국가 및 내부망 기반으로 접속을 차단합니다.  
주의: 국가별로 차단시 프록시 서버가 Super DMZ에 위치해야합니다.

## Preview
![image](https://user-images.githubusercontent.com/13088077/127341209-05c587d2-4ab2-4275-89ca-46f6aed8a8be.png)  
[설정파일 방식]  
  
![image](https://user-images.githubusercontent.com/13088077/127341582-15438bba-a376-41c4-b5e9-bb8f9648ee53.png)  
[구동 화면]  

## 설정 방법
```html
proxy:
  - type: in # in: 인바운드, out: 아웃바운드(미구현)
    name: example-1 # 프록시의 이름
    bindPort: 8080 # 바인드할 포트
    forwardHost: 10.1.3.200 # 전달할 서버의 아이피
    forwardPort: 80 # 전달할 서버의 포트
    allowedCountries: [KR, US, private, localhost] # 국가코드: KR, JP 등등.., private: 내부망, localhost: 루프백
  - type: in
    name: example-2
    bindPort: 8081
    forwardHost: 10.1.3.200
    forwardPort: 8080
    allowedCountries: [private]
```
