<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!DOCTYPE html>
<html>
<head>
    <title>App 2</title>
</head>
<body>
<h3>Hello, ${username} (${email})</h3>
<div>Link to <a href="${pageContext.request.contextPath}/documents">documents page</a></div>
<div>Link to <a href="${pageContext.request.contextPath}/onpremise">on-premise page</a></div>
<br/>
</body>
</html>