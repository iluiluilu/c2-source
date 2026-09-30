<#import "template.ftl" as layout>
<#--
  Forced password change on first login (Keycloak UPDATE_PASSWORD required
  action). Not overriding this file left it falling back to Keycloak's stock
  `base` theme, unstyled and mixed with English strings — this brings it in
  line with login.ftl (same kc-form-group/kc-input/kc-btn-primary classes).
-->
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-confirm'); section>
    <#if section = "header">
        Đổi mật khẩu
    <#elseif section = "form">
        <div id="kc-form">
            <p class="kc-form-hint">Đây là lần đăng nhập đầu tiên hoặc mật khẩu của bạn cần được đặt lại. Vui lòng đặt mật khẩu mới trước khi tiếp tục.</p>
            <form id="kc-passwd-update-form" action="${url.loginAction}" method="post">
                <input type="text" id="username" name="username" value="${username}" autocomplete="username" readonly="readonly" style="display:none;"/>
                <input type="password" id="password" name="password" autocomplete="current-password" style="display:none;"/>

                <div class="kc-form-group">
                    <label for="password-new" class="kc-label">Mật khẩu mới</label>
                    <input tabindex="1" id="password-new" class="kc-input" name="password-new" type="password"
                           autofocus autocomplete="new-password"
                           aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true</#if>" />
                    <#if messagesPerField.existsError('password')>
                        <span id="input-error-password" class="kc-input-error" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('password'))?no_esc}
                        </span>
                    </#if>
                </div>

                <div class="kc-form-group">
                    <label for="password-confirm" class="kc-label">Nhập lại mật khẩu mới</label>
                    <input tabindex="2" id="password-confirm" class="kc-input" name="password-confirm" type="password"
                           autocomplete="new-password"
                           aria-invalid="<#if messagesPerField.existsError('password-confirm')>true</#if>" />
                    <#if messagesPerField.existsError('password-confirm')>
                        <span id="input-error-password-confirm" class="kc-input-error" aria-live="polite">
                            ${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}
                        </span>
                    </#if>
                </div>

                <div id="kc-form-buttons">
                    <#if isAppInitiatedAction??>
                        <input tabindex="3" class="kc-btn-primary" type="submit" value="Đổi mật khẩu"/>
                        <button tabindex="4" class="kc-btn-secondary" type="submit" name="cancel-aia" value="true">Hủy</button>
                    <#else>
                        <input tabindex="3" class="kc-btn-primary" type="submit" value="Đổi mật khẩu"/>
                    </#if>
                </div>
            </form>
        </div>
    </#if>
</@layout.registrationLayout>
