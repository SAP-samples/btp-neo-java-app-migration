sap.ui.define([
	"sap/ui/model/json/JSONModel",
	"sap/ui/Device"
], function (JSONModel, Device) {
	"use strict";

	var oEmployeeModel;

	var sApiPath = "/java/management";
	var sUserInfoPath = "/java/userinfo";

	async function getToken() {
		var response = await fetch(sApiPath, {
			method: 'GET',
			headers: {
				"X-CSRF-Token": "fetch",
				"Content-Type": "application/json"
			}
		});
		var xcsrf = response.headers.get("X-CSRF-Token")

		return xcsrf;
	};

	return {

		createDeviceModel: function () {
			var oModel = new JSONModel(Device);
			oModel.setDefaultBindingMode("OneWay");
			return oModel;
		},

		createCurrentUserModel: function () {
			return new Promise(function (res, rej) {
				var oUserModel = new JSONModel();
				oUserModel.loadData(sUserInfoPath)
					.then(function () {
						res(oUserModel)
					})
					.catch(rej);
			});
		},

		createEmployeeModel: function () {
			return new Promise(function (res, rej) {
				oEmployeeModel = new JSONModel();
				oEmployeeModel.setDefaultBindingMode("TwoWay");
				oEmployeeModel.loadData(sApiPath)
					.then(function () {
						res(oEmployeeModel)
					})
					.catch(rej);
			});
		},

		refreshEmployeeModel: function () {
			return new Promise(function (res, rej) {
				oEmployeeModel.loadData(sApiPath)
					.then(function () {
						res(oEmployeeModel)
					})
					.catch(rej);
			});
		},

		addEmployee: async function (employee) {
			var token = await getToken();
			var oFomrData = new FormData();

			oFomrData.append("fname", employee.firstName);
			oFomrData.append("lname", employee.lastName);
			oFomrData.append("file", employee.avatar, employee.avatar.name)

			var response = await fetch(sApiPath, {
				method: 'POST',
				headers: {
					"X-CSRF-Token": token,
				},
				body: oFomrData
			});

			var result = await response.text();
			return result;
		},

		deleteEmployee: async function (id) {
			var token = await getToken();
			var response = await fetch(sApiPath + '?personId=' + id, {
				method: 'DELETE',
				headers: {
					"X-CSRF-Token": token,
				}
			});

			var result = await response.text();
			return result;
		},

		createNewEmployeeModel: function () {
			var oModel = new JSONModel({
				firstName: "",
				lastName: "",
				avatar: null
			});
			oModel.setDefaultBindingMode("TwoWay");
			return oModel;
		}

	};
});