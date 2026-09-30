<#--
  Custom chrome for the AISmartStore login theme. Reproduces the white card
  from the web landing page (web/src/app/page.tsx): navy grid background,
  navy header with a red bottom border + star logo + Vietnamese system title,
  and the encrypted-link footer note. The <#nested> hooks keep the standard
  registrationLayout contract so inherited base flows still render.
-->
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html<#if realm.internationalizationEnabled> lang="${locale.currentLanguageTag}"</#if>>

<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <meta name="robots" content="noindex, nofollow">
    <title>${msg("loginTitle",(realm.displayName!'AISmartStore'))}</title>
    <link rel="icon" href="${url.resourcesPath}/img/favicon.ico" />
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if authenticationSession??>
        <script type="module">
            import { checkCookiesAndSetTimer } from "${url.resourcesPath}/js/authChecker.js";
            checkCookiesAndSetTimer(
              "${authenticationSession.authSessionId}",
              "${authenticationSession.tabId}",
              "${url.ssoLoginInOtherTabsUrl?no_esc}"
            );
        </script>
    </#if>
</head>

<body class="${bodyClass}">
  <div class="kc-card">
    <#-- Header: navy bar, red bottom border, star logo + system title -->
    <div class="kc-card-header">
      <div class="kc-logo">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path fill-rule="evenodd" d="M10.788 3.21c.448-1.077 1.976-1.077 2.424 0l2.082 5.007 5.404.433c1.164.093 1.636 1.545.749 2.305l-4.117 3.527 1.257 5.273c.271 1.136-.964 2.033-1.96 1.425L12 18.354 7.373 21.18c-.996.608-2.231-.29-1.96-1.425l1.257-5.273-4.117-3.527c-.887-.76-.415-2.212.749-2.305l5.404-.433 2.082-5.006z" clip-rule="evenodd" />
        </svg>
      </div>
      <div>
        <h1 class="kc-title">HỆ THỐNG LƯU TRỮ DỮ LIỆU</h1>
        <h1 class="kc-title">&amp; XỬ LÝ THÔNG TIN AI</h1>
      </div>
    </div>

    <#-- Body -->
    <div class="kc-card-body">
      <div>
        <h1 id="kc-page-title"><#nested "header"></h1>
        <p class="kc-subtitle">Xác thực danh tính để truy cập phạm vi được phân công</p>
      </div>

      <#-- Feedback messages (auth errors, info, ...) -->
      <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
        <div class="kc-alert alert-${message.type}">
          <span>${kcSanitize(message.summary)?no_esc}</span>
        </div>
      </#if>

      <#nested "form">

      <#if auth?has_content && auth.showTryAnotherWayLink()>
        <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post">
          <div class="kc-form-group">
            <input type="hidden" name="tryAnotherWay" value="on"/>
            <a href="#" id="try-another-way"
               onclick="document.forms['kc-select-try-another-way-form'].submit();return false;">${msg("doTryAnotherWay")}</a>
          </div>
        </form>
      </#if>

      <#nested "socialProviders">

      <#if displayInfo>
        <div id="kc-info">
          <#nested "info">
        </div>
      </#if>

      <#-- Footer: matches the web landing page network note -->
      <div class="kc-footer-note">
        <span class="kc-status-dot"></span>
        <span>Kết nối nội bộ mã hóa TLS · VLAN 108 · Cô lập Internet (NFR-08, NFR-11)</span>
      </div>
    </div>
  </div>
</body>
</html>
</#macro>
