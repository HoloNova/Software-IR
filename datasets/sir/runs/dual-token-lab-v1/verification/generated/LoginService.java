package example.dualtoken.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import example.dualtoken.api.BadLoginException;
import example.dualtoken.api.LoginInput;
import example.dualtoken.api.TokenPairView;
import example.dualtoken.domain.AccessToken;
import example.dualtoken.domain.RefreshToken;
import example.dualtoken.domain.TokenPair;
import example.dualtoken.domain.User;
import example.dualtoken.persistence.AccessTokenMapper;
import example.dualtoken.persistence.RefreshTokenMapper;
import example.dualtoken.persistence.SecretDigest;
import example.dualtoken.persistence.TokenPairMapper;
import example.dualtoken.persistence.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class LoginService {

    private final UserMapper userMapper;
    private final AccessTokenMapper accessTokenMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final TokenPairMapper tokenPairMapper;

    public LoginService(UserMapper userMapper, AccessTokenMapper accessTokenMapper, RefreshTokenMapper refreshTokenMapper, TokenPairMapper tokenPairMapper) {
        this.userMapper = userMapper;
        this.accessTokenMapper = accessTokenMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.tokenPairMapper = tokenPairMapper;
    }

    public TokenPairView login(LoginInput input) {
        var holderMatches = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getName, input.getName())
                .last("LIMIT 2"));
        if (holderMatches.isEmpty()) {
            throw new BadLoginException();
        }
        if (holderMatches.size() > 1) {
            throw new IllegalStateException("more than one User row has the same name");
        }
        var holder = holderMatches.get(0);
        if (!(SecretDigest.matches(input.getPassword(), holder.getPasswordHash()))) {
            throw new BadLoginException();
        }
        var access = new AccessToken();
        access.setOwnerId(holder.getId());
        access.setExpiresAt((java.time.Instant.now()).plus(15L, java.time.temporal.ChronoUnit.MINUTES));
        var refresh = new RefreshToken();
        refresh.setOwnerId(holder.getId());
        refresh.setUsed(false);
        refresh.setExpiresAt((java.time.Instant.now()).plus(7L, java.time.temporal.ChronoUnit.DAYS));
        var pair = new TokenPair();
        pair.setAccessToken(access.getId());
        pair.setAccessExpiresAt(access.getExpiresAt());
        pair.setRefreshToken(refresh.getId());
        pair.setRefreshExpiresAt(refresh.getExpiresAt());
        if (access.getId() == null) {
            access.setId(java.util.UUID.randomUUID());
        }
        accessTokenMapper.insert(access);
        if (refresh.getId() == null) {
            refresh.setId(java.util.UUID.randomUUID());
        }
        refreshTokenMapper.insert(refresh);
        tokenPairMapper.insert(pair);
        return new TokenPairView(pair.getAccessToken(), pair.getAccessExpiresAt(), pair.getRefreshToken(), pair.getRefreshExpiresAt());
    }
}
