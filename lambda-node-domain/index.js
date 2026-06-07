const express = require("express");

const app = express();
app.use(express.json());

app.post("/invoke", async (req, res) => {
    const body = req.body;

    if (body.forceError === true) {
        return res.status(500).json({
            status: "ERROR",
            message: "Forced domain error"
        });
    }

    return res.status(200).json({
        status: "APPROVED",
        transactionId: cryptoRandomId(),
        receivedPayload: body,
        processedAt: new Date().toISOString()
    });
});

function cryptoRandomId() {
    return "tx-" + Math.random().toString(36).substring(2, 12);
}

app.listen(process.env.PORT || 3001, () => {
    console.log(`Lambda Node Domain running on port ${process.env.PORT || 3001}`);
});