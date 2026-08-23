package cn.qihangerp.api.controller;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
public class HomeController {
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private RedisTemplate<String,String> redisTemplate;
    @GetMapping("/")
    public String home(){
        // 嵌入式前端：根路径直接跳转到登录页入口
        return "<script>location.href='/index.html'</script>";
    }
    @GetMapping(value = "/echo-rest")
    public String rest() {
        return restTemplate.getForObject("http://tao-oms/test/na", String.class);
    }

}
