var azureUpload = {
    uploadFiles: function (params, successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, "AzureUpload", "uploadFiles", [params]);
    }
};

module.exports = azureUpload;
