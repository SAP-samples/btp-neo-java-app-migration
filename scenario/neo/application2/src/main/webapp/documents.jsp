<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<!DOCTYPE html>
<html>
<head>
    <title>App 2</title>
</head>
<body>
<h3>Documents:</h3>
<table>
    <tr>
        <th>ID</th>
        <th>Document</th>
        <th>Delete</th>
    </tr>
<c:forEach items="${documents}" var="docentry">
    <tr>
        <td>${docentry.key}</td>
        <td><img src="${docentry.value}" alt="Img" width="500" height="300"></td>
        <td>
            <form action="${pageContext.request.contextPath}/api/documents" method="post">
                <input type="hidden" name="action" value="delete">
                <input type="hidden" name="documentId" value="${docentry.key}">
                <input type="submit" value="Delete">
            </form>
        </td>
    </tr>
</c:forEach>
</table>
<form action="${pageContext.request.contextPath}/api/documents" method="post" enctype="multipart/form-data">
    <input type="file" name="file" size="10" accept="image/png, image/jpeg">
    <input type="submit" value="Upload File">
</form>
<br/>
</body>
</html>