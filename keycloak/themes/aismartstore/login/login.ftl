<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
    <#if section = "header">
        Đăng nhập hệ thống
    <#elseif section = "form">
        <div id="kc-form">
          <#if realm.password>
            <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
                <#if !usernameHidden??>
                    <div class="kc-form-group">
                        <label for="username" class="kc-label"><#if !realm.loginWithEmailAllowed>Tên đăng nhập<#elseif !realm.registrationEmailAsUsername>Tên đăng nhập hoặc email<#else>Email</#if></label>
                        <input tabindex="2" id="username" class="kc-input" name="username" value="${(login.username!'')}" type="text" autofocus autocomplete="username"
                               aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>" />
                        <#if messagesPerField.existsError('username','password')>
                            <span id="input-error" class="kc-input-error" aria-live="polite">
                                ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                            </span>
                        </#if>
                    </div>
                </#if>

                <div class="kc-form-group">
                    <label for="password" class="kc-label">Mật khẩu</label>
                    <input tabindex="3" id="password" class="kc-input" name="password" type="password" autocomplete="current-password"
                           aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>" />
                    <#if usernameHidden?? && messagesPerField.existsError('username','password')>
                        <span id="input-error" class="kc-input-error" aria-live="polite">
                            ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                        </span>
                    </#if>
                </div>

                <#if (realm.rememberMe && !usernameHidden??) || realm.resetPasswordAllowed>
                    <div class="kc-form-options">
                        <#if realm.rememberMe && !usernameHidden??>
                            <label>
                                <#if login.rememberMe??>
                                    <input tabindex="5" id="rememberMe" name="rememberMe" type="checkbox" checked> Ghi nhớ đăng nhập
                                <#else>
                                    <input tabindex="5" id="rememberMe" name="rememberMe" type="checkbox"> Ghi nhớ đăng nhập
                                </#if>
                            </label>
                        <#else>
                            <span></span>
                        </#if>
                        <#if realm.resetPasswordAllowed>
                            <a tabindex="6" href="${url.loginResetCredentialsUrl}">Quên mật khẩu?</a>
                        </#if>
                    </div>
                </#if>

                <div id="kc-form-buttons">
                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <input tabindex="7" class="kc-btn-primary" name="login" id="kc-login" type="submit" value="Đăng nhập"/>
                </div>
            </form>
          </#if>
        </div>
    <#elseif section = "info">
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration">
                <span>${msg("noAccount")} <a tabindex="8" href="${url.registrationUrl}">${msg("doRegister")}</a></span>
            </div>
        </#if>
    <#elseif section = "socialProviders">
        <#if realm.password && social.providers??>
            <div id="kc-social-providers">
                <ul>
                    <#list social.providers as p>
                        <li>
                            <a id="social-${p.alias}" href="${p.loginUrl}">${p.displayName!}</a>
                        </li>
                    </#list>
                </ul>
            </div>
        </#if>
    </#if>
</@layout.registrationLayout>
